package com.selenite.scanner.link

import android.content.Context
import android.util.Log
import com.selenite.scanner.data.ContentAssets
import com.selenite.scanner.data.ContentUpdateManager
import com.selenite.scanner.data.DatabaseSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Registration seam for the Android-only installer, mirroring how
 * `ContentUpdatePrompt.registerForceChecker` lets shared UI reach Android-only content
 * work. `MainActivity` registers this with the `DatabaseSyncManager` it already builds.
 */
object MirorLinkContentInstaller {
    private var installer: (suspend (File, String, Int) -> ContentUpdateManager.LocalPackageResult)? = null

    fun register(block: suspend (File, String, Int) -> ContentUpdateManager.LocalPackageResult) {
        installer = block
    }

    internal suspend fun install(
        dir: File,
        tag: String,
        version: Int,
    ): ContentUpdateManager.LocalPackageResult =
        installer?.invoke(dir, tag, version)
            ?: ContentUpdateManager.LocalPackageResult.Rejected("Card data updates are unavailable.")
}

/**
 * Link's own private directory tree, deliberately outside `ContentAssets.stagingDir()`.
 *
 * `ContentAssets.clearStaging` deletes that whole `.staging` tree and is called from
 * both `ContentUpdateManager.download` and `autoUpdateOnStartup`. Sharing it would make
 * an ordinary network update pass delete an in-flight Link transfer.
 */
private fun linkRoot(context: Context): File = File(context.filesDir, "miror_link")

/**
 * Clears Link's staging tree and session-only peer-photo cache at process start.
 *
 * The coordinator already abandons staging on every in-session terminal path, so the only
 * way a transfer's bytes or a received photo outlive their session is process death before
 * normal teardown. Before this existed those files could sit in app-private storage until
 * the next time the user happened to open Link, which for someone who tried it once and
 * moved on is never.
 *
 * Deliberately not a member of the gateway: this must run on every launch, including on
 * devices where Link is unavailable and no gateway is ever constructed.
 */
object MirorLinkStagingJanitor {

    /**
     * Once per process, never per Activity.
     *
     * `MainActivity` declares no `configChanges`, so `onCreate` runs again on a rotation
     * or the automatic theme change at sunset -- the same hazard `MirorLinkHost` is
     * process-scoped to avoid. Reclaiming on every `onCreate` would delete the staging
     * tree of a content transfer that is still running, since the coordinator outlives
     * the Activity that started it.
     *
     * A genuine cold start is the only moment nothing can be in flight, and it is the
     * only moment an orphan can exist -- every in-session path already abandons staging
     * and clears received photos.
     */
    @Volatile
    private var reclaimedThisProcess = false

    fun reclaim(context: Context) {
        if (reclaimedThisProcess) return
        synchronized(this) {
            if (reclaimedThisProcess) return
            reclaimedThisProcess = true
        }
        runCatching {
            val appContext = context.applicationContext
            var reclaimed = false
            val roots = listOf(
                linkRoot(appContext),
                File(appContext.cacheDir, MIROR_LINK_PHOTO_CACHE_DIR),
            )
            roots.forEach { root ->
                if (root.isDirectory) {
                    root.deleteRecursively()
                    reclaimed = true
                }
            }
            if (reclaimed) {
                Log.i("MirorLinkStaging", "Reclaimed abandoned Link session files at startup")
            }
        }.onFailure { Log.w("MirorLinkStaging", "Could not reclaim Link session files at startup") }
    }
}

internal class AndroidMirorLinkContentGateway(context: Context) : MirorLinkContentGateway {

    private val appContext = context.applicationContext

    override fun capability(): MirorLinkContentCapability = MirorLinkContentCapability(
        canServe = true,
        canInstall = true,
        activeVersion = ContentUpdateManager.activeVersion(appContext),
    ).also {
        // Version numbers and tags only -- never a nickname, nonce, endpoint id, or
        // anything about the collection.
        Log.i(TAG, "Link content capability: active=v${it.activeVersion}")
    }

    /**
     * Cheap by construction. An over-the-air install has its three files on disk, so
     * `File.length()` is free. A bundle-only device -- every fresh install, and the first
     * donor in any relay chain -- has them compressed inside the APK, where
     * `AssetManager.openFd` throws and the only way to learn a size is to inflate the
     * whole thing. The plan makes size optional rather than paying that on entry, so this
     * reports null sizes and the prompt says the size is unavailable.
     */
    override suspend fun describeActive(): ContentOfferMessage? = withContext(Dispatchers.IO) {
        val version = ContentUpdateManager.activeVersion(appContext)
        val installedTag = ContentAssets.installedTag(appContext)
        val tag = installedTag ?: synthesizedBundleTag()
        val installedDir = installedTag?.let { ContentAssets.tagDir(appContext, it) }

        val files = ContentAssets.REQUIRED_FILES.map { name ->
            val size = installedDir
                ?.let { File(it, name) }
                ?.takeIf { it.isFile && it.length() > 0 }
                ?.length()
            MirorLinkContentFile(name, size)
        }
        val total = files.mapNotNull { it.sizeBytes }
            .takeIf { it.size == files.size }
            ?.sum()

        ContentOfferMessage(tag = tag, version = version, files = files, totalSizeBytes = total)
    }

    /**
     * Takes the coherent snapshot, after acceptance.
     *
     * Copying rather than streaming the live files is what makes it coherent: the active
     * content can be switched by a concurrent update mid-transfer, and
     * `ContentAssets.finishPending` prunes superseded tag directories. Reading through
     * `ContentAssets.open` also transparently covers the bundled case. Sizes and digests
     * fall out of this single pass, so the package is read exactly once.
     */
    override suspend fun openOutgoing(): MirorLinkOutgoingContent? = withContext(Dispatchers.IO) {
        val installedTag = ContentAssets.installedTag(appContext)
        val version = installedTag?.let(ContentAssets::parseVersion)
            ?: ContentUpdateManager.activeVersion(appContext)
        val tag = installedTag ?: synthesizedBundleTag()
        val installedDir = installedTag?.let { ContentAssets.tagDir(appContext, it) }
        val dir = File(linkRoot(appContext), "outgoing-${System.nanoTime()}")
        if (!dir.mkdirs() && !dir.isDirectory) {
            Log.e(TAG, "Could not create the Link outgoing directory")
            return@withContext null
        }

        try {
            val files = mutableListOf<MirorLinkContentFile>()
            val digests = mutableListOf<ByteArray>()
            ContentAssets.REQUIRED_FILES.forEach { name ->
                val target = File(dir, name)
                val digest = MessageDigest.getInstance("SHA-256")
                val input = installedDir?.let { File(it, name).inputStream() }
                    ?: ContentAssets.open(appContext, name)
                input.use {
                    target.outputStream().use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            val read = it.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                        }
                    }
                }
                files += MirorLinkContentFile(name, target.length())
                digests += digest.digest()
            }
            AndroidOutgoingContent(tag, version, dir, files, digests)
        } catch (_: Exception) {
            Log.e(TAG, "Could not snapshot the active content for Link")
            dir.deleteRecursively()
            null
        }
    }

    override fun beginStaging(sessionKey: String): MirorLinkContentStaging {
        val safeKey = sessionKey.filter { it.isLetterOrDigit() || it == '-' }.take(32)
        return AndroidLinkStaging(File(linkRoot(appContext), "staging-$safeKey-${System.nanoTime()}"))
    }

    override suspend fun installVerified(
        staging: MirorLinkContentStaging,
        tag: String,
        version: Int,
    ): MirorLinkContentInstallResult {
        val androidStaging = staging as? AndroidLinkStaging
            ?: return MirorLinkContentInstallResult.Rejected("The update could not be read.")
        if (!androidStaging.isComplete()) {
            androidStaging.abandon()
            return MirorLinkContentInstallResult.Rejected("The update was incomplete.")
        }
        // The updater mutex is acquired inside installVerifiedPackage, not around the
        // wireless transfer that produced this directory.
        return when (val result = MirorLinkContentInstaller.install(androidStaging.dir, tag, version)) {
            ContentUpdateManager.LocalPackageResult.Applied -> {
                androidStaging.abandon()
                MirorLinkContentInstallResult.Applied
            }

            ContentUpdateManager.LocalPackageResult.Busy -> {
                androidStaging.abandon()
                MirorLinkContentInstallResult.Busy
            }

            ContentUpdateManager.LocalPackageResult.Superseded -> {
                androidStaging.abandon()
                MirorLinkContentInstallResult.Superseded
            }

            is ContentUpdateManager.LocalPackageResult.Rejected -> {
                androidStaging.abandon()
                MirorLinkContentInstallResult.Rejected(result.reason)
            }
        }
    }

    /**
     * An interrupted transfer must not strand tens of megabytes. Scoped strictly to
     * Link's own tree, so it can never touch `ContentAssets` state or a pending receipt.
     */
    override fun cleanupAbandonedStaging() {
        runCatching {
            val root = linkRoot(appContext)
            if (root.isDirectory) root.deleteRecursively()
        }.onFailure { Log.w(TAG, "Could not reclaim Link staging") }
    }

    private fun synthesizedBundleTag(): String =
        "content-v${DatabaseSyncManager.CURRENT_ASSET_VERSION}"

    companion object {
        private const val TAG = "MirorLinkContent"
        private const val COPY_BUFFER_BYTES = 64 * 1024
    }
}

private class AndroidOutgoingContent(
    override val tag: String,
    override val version: Int,
    private val dir: File,
    override val files: List<MirorLinkContentFile>,
    private val digests: List<ByteArray>,
) : MirorLinkOutgoingContent {

    private val handles = mutableMapOf<Int, RandomAccessFile>()

    override fun digest(fileIndex: Int): ByteArray = digests[fileIndex]

    override fun read(fileIndex: Int, offset: Long, maxBytes: Int): ByteArray {
        val file = files.getOrNull(fileIndex) ?: return ByteArray(0)
        val handle = handles.getOrPut(fileIndex) {
            RandomAccessFile(File(dir, file.name), "r")
        }
        handle.seek(offset)
        val size = file.sizeBytes ?: 0L
        val remaining = (size - offset).coerceAtMost(maxBytes.toLong()).toInt()
        if (remaining <= 0) return ByteArray(0)
        val buffer = ByteArray(remaining)
        handle.readFully(buffer)
        return buffer
    }

    override fun close() {
        handles.values.forEach { runCatching { it.close() } }
        handles.clear()
        runCatching { dir.deleteRecursively() }
    }
}

private class AndroidLinkStaging(val dir: File) : MirorLinkContentStaging {

    private class Entry(
        val name: String,
        val declaredSize: Long,
        val stream: java.io.OutputStream,
        val digest: MessageDigest,
        var written: Long = 0,
        var verified: Boolean = false,
    )

    private val entries = mutableMapOf<Int, Entry>()

    init {
        dir.mkdirs()
    }

    override fun beginFile(index: Int, name: String, sizeBytes: Long) {
        // Only the three names the content contract defines are ever written, so a peer
        // cannot steer bytes at a path of its choosing.
        require(name in ContentAssets.REQUIRED_FILES) { "Unexpected Link content file $name" }
        require(index in ContentAssets.REQUIRED_FILES.indices) { "Unexpected Link content index $index" }
        require(index !in entries) { "Duplicate Link content file $index" }
        require(sizeBytes in 1..MirorLinkProtocol.MAX_CONTENT_FILE_BYTES) {
            "Link content file exceeds the storage budget"
        }
        val target = File(dir, name)
        entries[index] = Entry(
            name = name,
            declaredSize = sizeBytes,
            stream = target.outputStream().buffered(),
            digest = MessageDigest.getInstance("SHA-256"),
        )
    }

    override fun write(index: Int, bytes: ByteArray) {
        val entry = entries[index] ?: error("Link content file $index was not started")
        require(!entry.verified) { "Link content file $index was already finished" }
        require(entry.written + bytes.size <= entry.declaredSize) {
            "Link content file $index exceeds its declared length"
        }
        // Never buffered whole: written straight through to app-private storage.
        entry.stream.write(bytes)
        entry.digest.update(bytes)
        entry.written += bytes.size
    }

    override fun finishFile(index: Int, expectedBytes: Long, expectedSha256: ByteArray): Boolean {
        val entry = entries[index] ?: return false
        runCatching { entry.stream.flush(); entry.stream.close() }
        val actualDigest = entry.digest.digest()
        val target = File(dir, entry.name)
        val lengthOk = expectedBytes == entry.declaredSize &&
            entry.written == entry.declaredSize &&
            target.length() == entry.declaredSize
        val digestOk = actualDigest.contentEquals(expectedSha256)
        entry.verified = lengthOk && digestOk
        if (!entry.verified) {
            Log.w(
                TAG,
                "Rejected ${entry.name}: lengthOk=$lengthOk digestOk=$digestOk " +
                    "(${entry.written} of $expectedBytes bytes)",
            )
        }
        return entry.verified
    }

    fun isComplete(): Boolean =
        entries.size == ContentAssets.REQUIRED_FILES.size &&
            entries.values.all { it.verified } &&
            ContentAssets.isComplete(dir)

    override fun abandon() {
        entries.values.forEach { runCatching { it.stream.close() } }
        entries.clear()
        runCatching { dir.deleteRecursively() }
    }

    companion object {
        private const val TAG = "MirorLinkStaging"
    }
}
