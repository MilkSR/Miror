package com.selenite.scanner.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Resolves content assets to either an over-the-air install or the APK bundle.
 *
 * Layout under filesDir:
 *
 *     content/
 *       current            text file holding the installed tag, e.g. "content-v11"
 *       pending            complete downloaded tag awaiting/finishing DB merge
 *       content-v11/       the files that tag shipped
 *       .staging/          partial downloads, never read
 *
 * The `current` marker is what makes an install atomic. Files are downloaded
 * into a tag directory first and only become live when the marker is rewritten,
 * which is a single small write. A crash mid-download leaves the marker pointing
 * at the previous tag, so the app keeps running on the content it already had.
 */
object ContentAssets {
    const val CARDS_DB = "cards.db"
    const val INDEX_BIN = "master_index_v3.bin"
    const val INDEX_IDS = "master_index_v3_ids.txt"

    /** Every file a content release must supply. Partial sets are rejected. */
    val REQUIRED_FILES = listOf(CARDS_DB, INDEX_BIN, INDEX_IDS)

    private const val TAG = "ContentAssets"
    private const val ROOT_DIR = "content"
    private const val MARKER = "current"
    private const val PENDING_MARKER = "pending"
    private const val STAGING_DIR = ".staging"
    private val CONTENT_TAG = Regex("""content-v(\d+)""")

    fun root(context: Context): File = File(context.filesDir, ROOT_DIR)

    fun stagingDir(context: Context): File = File(root(context), STAGING_DIR)

    fun tagDir(context: Context, tag: String): File = File(root(context), tag)

    /**
     * The installed tag, or null when nothing installed over the air is newer
     * than what this APK bundles.
     *
     * The version guard is load-bearing, not a nicety. An app update can ship a
     * bundled catalog newer than the last over-the-air install -- say an OTA
     * install of v12 followed by an APK carrying v13. Without this check the
     * sidecar would read the stale v12 files while the version gate compared
     * against v13, merging old rows and then recording them as new, so the
     * bundled v13 content would never be applied. The embedding gallery would
     * go stale the same way, which is the torn state that produces cards you
     * can see but cannot scan.
     */
    fun installedTag(context: Context): String? {
        val marker = File(root(context), MARKER)
        if (!marker.isFile) return null
        val tag = runCatching { marker.readText().trim() }.getOrNull()
        if (tag.isNullOrEmpty()) return null
        val version = parseVersion(tag) ?: return null
        if (version <= DatabaseSyncManager.CURRENT_ASSET_VERSION) {
            // The APK caught up with or overtook this install; the bundle wins.
            Log.i(TAG, "Bundled content v${DatabaseSyncManager.CURRENT_ASSET_VERSION} supersedes $tag")
            return null
        }
        return if (isComplete(tagDir(context, tag))) tag else null
    }

    /**
     * Deletes over-the-air content the APK has caught up with. Safe to call at
     * any time: it only removes files the bundle can already supply.
     */
    fun pruneSuperseded(context: Context) {
        val root = root(context)
        if (!root.isDirectory) return
        runCatching {
            root.listFiles()?.forEach { child ->
                val version = if (child.isDirectory) parseVersion(child.name) else null
                if (version != null && version <= DatabaseSyncManager.CURRENT_ASSET_VERSION) {
                    Log.i(TAG, "Pruning superseded content ${child.name}")
                    child.deleteRecursively()
                }
            }
            if (installedTag(context) == null) File(root, MARKER).delete()
            val pending = readMarker(File(root, PENDING_MARKER))
            val pendingVersion = pending?.let(::parseVersion)
            if (pendingVersion != null && pendingVersion <= DatabaseSyncManager.CURRENT_ASSET_VERSION) {
                clearPending(context, pending)
            }
        }.onFailure { Log.w(TAG, "Prune failed", it) }
    }

    /**
     * Reclaims tag directories no marker refers to.
     *
     * [finishPending] prunes old tags once an install completes, but an install
     * that never completes leaves its directory behind: download v12, fail to
     * apply it, then supersede it with v13, and v12's ~57MB is stranded with
     * nothing left pointing at it. On a device short of space that is exactly
     * the storage a user cannot account for or reclaim.
     *
     * Deliberately keyed on the raw marker text rather than [installedTag] or
     * [pendingTag], which apply version filters and would report null for a
     * directory that is still the live recovery source. Anything the active or
     * pending marker names is kept; everything else is already unreachable.
     */
    fun pruneOrphanedTags(context: Context) {
        val root = root(context)
        if (!root.isDirectory) return
        runCatching {
            val keep = setOfNotNull(
                readMarker(File(root, MARKER)),
                readMarker(File(root, PENDING_MARKER)),
            )
            root.listFiles()?.forEach { child ->
                if (child.isDirectory && child.name != STAGING_DIR && child.name !in keep) {
                    Log.i(TAG, "Reclaiming orphaned content ${child.name}")
                    child.deleteRecursively()
                }
            }
        }.onFailure { Log.w(TAG, "Orphaned-tag prune failed", it) }
    }

    /**
     * Numeric version parsed from the installed tag, or null. Tags are
     * `content-vN`, where N shares its numbering with the bundled
     * [DatabaseSyncManager.CURRENT_ASSET_VERSION].
     */
    fun installedVersion(context: Context): Int? =
        installedTag(context)?.let { parseVersion(it) }

    fun parseVersion(tag: String): Int? =
        CONTENT_TAG.matchEntire(tag)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * A complete downloaded release whose DB merge/asset activation has not
     * been durably acknowledged yet.
     *
     * Invalid, incomplete, or APK-superseded receipts are discarded. A valid
     * receipt is intentionally independent of the network so startup can finish
     * an interrupted install while the device is offline.
     */
    fun pendingTag(context: Context): String? {
        val marker = File(root(context), PENDING_MARKER)
        val tag = readMarker(marker) ?: return null
        val version = parseVersion(tag)
        if (version == null) {
            Log.w(TAG, "Discarding invalid pending content tag $tag")
            marker.delete()
            return null
        }
        val dir = tagDir(context, tag)
        if (version <= DatabaseSyncManager.CURRENT_ASSET_VERSION) {
            Log.i(TAG, "Bundled content supersedes pending $tag")
            marker.delete()
            dir.deleteRecursively()
            return null
        }
        if (!isComplete(dir)) {
            Log.w(TAG, "Discarding incomplete pending content $tag")
            marker.delete()
            dir.deleteRecursively()
            return null
        }
        return tag
    }

    /**
     * Persists the recovery point before touching the live card catalog.
     * Existing content remains active; this only makes the completed tag
     * resumable.
     */
    fun markPending(context: Context, tag: String): Boolean {
        val version = parseVersion(tag)
        if (version == null || version <= DatabaseSyncManager.CURRENT_ASSET_VERSION) {
            Log.e(TAG, "Refusing invalid or APK-superseded pending tag $tag")
            return false
        }
        if (!isComplete(tagDir(context, tag))) {
            Log.e(TAG, "Refusing to mark incomplete content pending for $tag")
            return false
        }
        return writeMarkerAtomically(File(root(context), PENDING_MARKER), tag)
    }

    /** Clears only the receipt the caller actually finished. */
    fun clearPending(context: Context, expectedTag: String) {
        val marker = File(root(context), PENDING_MARKER)
        if (readMarker(marker) == expectedTag) {
            marker.delete()
            File(root(context), "$PENDING_MARKER.tmp").delete()
        }
    }

    /**
     * Acknowledges a fully applied pending release, then reclaims older tag
     * directories. Cleanup happens after DB commit, marker activation, version
     * acknowledgement, and the serialized gallery-reload request have all
     * returned successfully.
     */
    fun finishPending(context: Context, expectedTag: String) {
        clearPending(context, expectedTag)
        runCatching { pruneOtherTags(context, keep = expectedTag) }
            .onFailure { Log.w(TAG, "Installed $expectedTag, but old-tag cleanup failed", it) }
    }

    /** True when a directory holds every required file at non-zero length. */
    fun isComplete(dir: File): Boolean =
        dir.isDirectory && REQUIRED_FILES.all { File(dir, it).let { f -> f.isFile && f.length() > 0 } }

    /**
     * The installed copy of [name] if one exists, otherwise null. Callers that
     * can read from the APK bundle should use [open] instead.
     */
    fun resolve(context: Context, name: String): File? {
        val tag = installedTag(context) ?: return null
        val file = File(tagDir(context, tag), name)
        return if (file.isFile && file.length() > 0) file else null
    }

    /**
     * Opens [name], preferring an over-the-air install and falling back to the
     * APK bundle. The bundle is always present, so this never returns a
     * half-updated state -- a missing or truncated download simply falls back.
     */
    fun open(context: Context, name: String): InputStream {
        resolve(context, name)?.let { file ->
            return runCatching { file.inputStream() as InputStream }
                .onFailure { Log.w(TAG, "Falling back to bundled $name", it) }
                .getOrElse { context.assets.open(name) }
        }
        return context.assets.open(name)
    }

    /**
     * Opens the APK's own copy of [name], ignoring any install. Used to recover
     * when installed content turns out to be unreadable: the bundle always
     * ships as a matched, self-consistent set.
     */
    fun openBundled(context: Context, name: String): InputStream = context.assets.open(name)

    /**
     * Promotes a staged tag directory to live by atomically rewriting the
     * marker. Superseded directories are retained until [finishPending], so a
     * process death during final acknowledgement never destroys the recovery
     * source prematurely.
     */
    fun commit(context: Context, tag: String): Boolean {
        val dir = tagDir(context, tag)
        if (parseVersion(tag) == null || !isComplete(dir)) {
            Log.e(TAG, "Refusing to commit incomplete content set for $tag")
            return false
        }
        val marker = File(root(context), MARKER)
        if (!writeMarkerAtomically(marker, tag)) {
            Log.e(TAG, "Failed to commit $tag")
            return false
        }

        Log.i(TAG, "Content $tag is now live")
        return true
    }

    private fun readMarker(marker: File): String? =
        runCatching { marker.takeIf(File::isFile)?.readText()?.trim() }
            .getOrNull()
            ?.takeIf(String::isNotEmpty)

    private fun writeMarkerAtomically(marker: File, value: String): Boolean = runCatching {
        marker.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                error("Could not create content marker directory $parent")
            }
        }
        val tmp = File(marker.parentFile, "${marker.name}.tmp")
        FileOutputStream(tmp).use { output ->
            output.write(value.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(
                tmp.toPath(),
                marker.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tmp.toPath(),
                marker.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        readMarker(marker) == value
    }.onFailure {
        Log.e(TAG, "Could not write ${marker.name} marker", it)
    }.getOrDefault(false)

    private fun pruneOtherTags(context: Context, keep: String) {
        root(context).listFiles()?.forEach { child ->
            if (child.isDirectory && child.name != keep && child.name != STAGING_DIR) {
                child.deleteRecursively()
            }
        }
    }

    fun clearStaging(context: Context) {
        stagingDir(context).deleteRecursively()
    }
}
