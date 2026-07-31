package com.selenite.scanner.link

import com.selenite.scanner.data.OfflineCardRepository
import com.selenite.scanner.share.MirorShareDecodeResult
import com.selenite.scanner.share.SharedCollection
import com.selenite.scanner.share.ShareManifestBinaryCodec
import kotlinx.coroutines.flow.first

/**
 * A frozen collection snapshot ready to send.
 *
 * The payload is exactly `ShareManifestBinaryCodec.encode(...)`. It is **not**
 * deflated. The Sigil text path picks the smaller of raw and deflated bytes because
 * it pays for every character in a pasteable code; Link pays nothing for those bytes
 * and gains two things by staying raw: the SHA-256 in `SNAPSHOT_OFFER` binds exactly
 * what the decoder will parse, and there is no inflate step for a hostile peer to
 * aim a decompression bomb at.
 */
internal class MirorLinkOutgoingSnapshot(
    val bytes: ByteArray,
    val scope: MirorLinkSnapshotScope,
    /**
     * Exact card identities the user chose to expose in this snapshot.
     *
     * Photo requests are bound to this set so a peer cannot guess an id outside a
     * multi-select share and make the owner look it up in their full collection.
     */
    val sharedCardIds: Set<String> = emptySet(),
) {
    val sha256: ByteArray = MirorSha256.hash(bytes)

    val chunkCount: Int = if (bytes.isEmpty()) {
        0
    } else {
        (bytes.size + MirorLinkProtocol.MAX_SNAPSHOT_CHUNK_BYTES - 1) /
            MirorLinkProtocol.MAX_SNAPSHOT_CHUNK_BYTES
    }

    val isPresent: Boolean get() = scope != MirorLinkSnapshotScope.NONE

    fun offer(): SnapshotOfferMessage = SnapshotOfferMessage(
        present = isPresent,
        totalBytes = bytes.size,
        chunkCount = chunkCount,
        sha256 = sha256,
        scope = scope,
    )

    fun chunk(index: Int): SnapshotChunkMessage {
        require(index in 0 until chunkCount) { "Link snapshot chunk index is out of range" }
        val start = index * MirorLinkProtocol.MAX_SNAPSHOT_CHUNK_BYTES
        val end = minOf(bytes.size, start + MirorLinkProtocol.MAX_SNAPSHOT_CHUNK_BYTES)
        return SnapshotChunkMessage(index, bytes.copyOfRange(start, end))
    }

    companion object {
        val EMPTY = MirorLinkOutgoingSnapshot(
            ByteArray(0),
            MirorLinkSnapshotScope.NONE,
            emptySet(),
        )
    }
}

/**
 * Bounded reassembly. The buffer is sized once from the offer, which the offer
 * decoder has already checked against [ShareManifestBinaryCodec.MAX_MANIFEST_BYTES],
 * so a peer cannot drive allocation with chunk traffic.
 */
internal class MirorLinkSnapshotAssembler(private val offer: SnapshotOfferMessage) {
    private val buffer = ByteArray(offer.totalBytes)
    private val received = BooleanArray(offer.chunkCount)
    private var receivedCount = 0

    val expectedChunks: Int get() = offer.chunkCount
    val isComplete: Boolean get() = receivedCount == offer.chunkCount
    val progress: Float
        get() = if (offer.chunkCount == 0) 1f else receivedCount.toFloat() / offer.chunkCount

    /** Returns true when the chunk was new. Duplicates are ignored, not errors. */
    fun accept(chunk: SnapshotChunkMessage): Boolean {
        linkRequire(chunk.index in 0 until offer.chunkCount) {
            "Link snapshot chunk index is outside the offered range"
        }
        val start = chunk.index * MirorLinkProtocol.MAX_SNAPSHOT_CHUNK_BYTES
        val expectedSize = minOf(
            MirorLinkProtocol.MAX_SNAPSHOT_CHUNK_BYTES,
            offer.totalBytes - start,
        )
        linkRequire(chunk.data.size == expectedSize) {
            "Link snapshot chunk ${chunk.index} has the wrong length"
        }
        if (received[chunk.index]) return false
        chunk.data.copyInto(buffer, start)
        received[chunk.index] = true
        receivedCount++
        return true
    }

    /**
     * Verifies length and digest before the bytes are handed to the manifest decoder.
     * A digest mismatch is a transfer-integrity failure, not a claim about who sent it.
     */
    fun finish(): ByteArray {
        linkRequire(isComplete) { "Link snapshot is missing chunks" }
        linkRequire(buffer.size == offer.totalBytes) { "Link snapshot length disagrees with its offer" }
        val actual = MirorSha256.hash(buffer)
        linkRequire(actual.contentEquals(offer.sha256)) {
            "Link snapshot failed its transfer integrity check"
        }
        return buffer
    }
}

internal sealed interface MirorLinkSnapshotResolution {
    data class Resolved(val collection: SharedCollection) : MirorLinkSnapshotResolution

    /** The peer's manifest requires a format this build predates. */
    data class UpdateRequired(val message: String) : MirorLinkSnapshotResolution

    data class Invalid(val message: String) : MirorLinkSnapshotResolution
}

/**
 * Seam between the coordinator and the collection repository.
 *
 * The coordinator depends on this rather than on [OfflineCardRepository] directly so
 * the whole reciprocal state machine can be exercised in common tests without a Room
 * database standing behind it.
 */
internal interface MirorLinkSnapshotSource {
    suspend fun build(entryIds: Set<Long>?): MirorLinkOutgoingSnapshot
    suspend fun resolve(manifestBytes: ByteArray): MirorLinkSnapshotResolution

    /**
     * Path to the user's own photo for [cardId], or null when they never took one.
     * Consulted only when a peer asks for that specific card.
     */
    suspend fun localPhotoPath(cardId: String): String?
}

internal class MirorLinkSnapshotService(
    private val repository: OfflineCardRepository,
) : MirorLinkSnapshotSource {

    override suspend fun build(entryIds: Set<Long>?): MirorLinkOutgoingSnapshot {
        val manifest = repository.buildOwnedCollectionShareManifest(entryIds = entryIds)
        if (manifest.entries.isEmpty()) return MirorLinkOutgoingSnapshot.EMPTY
        val bytes = ShareManifestBinaryCodec.encode(manifest)
        val scope = if (entryIds == null) {
            MirorLinkSnapshotScope.FULL
        } else {
            MirorLinkSnapshotScope.SELECTED
        }
        return MirorLinkOutgoingSnapshot(
            bytes = bytes,
            scope = scope,
            sharedCardIds = manifest.entries.mapTo(LinkedHashSet()) { it.cardId },
        )
    }

    /**
     * Decodes and resolves with exactly the outcomes a Sigil gets: known cards
     * display, unknown identities stay explicitly unknown rather than resolving to a
     * different card, and a required future format asks for an app update.
     *
     * No inflate sniffing -- Link never compresses, so attempting one would only add
     * an attack surface.
     */
    override suspend fun localPhotoPath(cardId: String): String? {
        val snapshot = repository.getCollectionSnapshot().first()
        return snapshot.ownedItems
            .firstOrNull { it.entry.cardId == cardId && !it.entry.localImagePath.isNullOrBlank() }
            ?.entry
            ?.localImagePath
    }

    override suspend fun resolve(manifestBytes: ByteArray): MirorLinkSnapshotResolution =
        when (val decoded = ShareManifestBinaryCodec.decode(manifestBytes)) {
            is MirorShareDecodeResult.Success ->
                MirorLinkSnapshotResolution.Resolved(repository.resolveSharedCollection(decoded.manifest))

            is MirorShareDecodeResult.UnsupportedFormat ->
                MirorLinkSnapshotResolution.UpdateRequired(
                    "This collection was shared by a newer version of Miror. Update the app to open it.",
                )

            is MirorShareDecodeResult.RetiredFormat ->
                MirorLinkSnapshotResolution.UpdateRequired(
                    "This collection was shared by an older version of Miror and can no longer be opened.",
                )

            is MirorShareDecodeResult.InvalidData ->
                MirorLinkSnapshotResolution.Invalid(decoded.message)
        }
}
