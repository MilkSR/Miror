package com.selenite.scanner.link

import com.selenite.scanner.share.ShareManifestBinaryCodec

/**
 * Message payloads are a flat list of length-delimited fields:
 *
 *     field := varint id, byte flags (bit 0 = critical), varint length, bytes
 *
 * A reader keeps the ids it knows, skips unknown *optional* ids by their declared
 * length, and refuses the session when an unknown *critical* id appears. That is
 * the whole forward-compatibility story -- no version table, no schema registry.
 */
internal class MirorLinkFieldWriter(initialCapacity: Int = 64) {
    private val writer = MirorLinkWriter(initialCapacity)
    private var count = 0

    fun putBytes(id: Int, critical: Boolean, value: ByteArray) {
        require(count < MirorLinkProtocol.MAX_FIELDS_PER_MESSAGE) { "Too many Link fields" }
        count++
        writer.writeVarInt(id)
        writer.writeByte(if (critical) 1 else 0)
        writer.writeLengthPrefixed(value)
    }

    fun putVarInt(id: Int, critical: Boolean, value: Int) {
        val body = MirorLinkWriter(8).apply { writeVarInt(value) }.toByteArray()
        putBytes(id, critical, body)
    }

    fun putVarLong(id: Int, critical: Boolean, value: Long) {
        val body = MirorLinkWriter(12).apply { writeVarLong(value) }.toByteArray()
        putBytes(id, critical, body)
    }

    fun putBool(id: Int, critical: Boolean, value: Boolean) =
        putBytes(id, critical, byteArrayOf(if (value) 1 else 0))

    fun putString(id: Int, critical: Boolean, value: String) =
        putBytes(id, critical, value.encodeToByteArray())

    fun toByteArray(): ByteArray = writer.toByteArray()
}

internal class MirorLinkFieldSet private constructor(
    private val values: Map<Int, ByteArray>,
    private val criticalIds: Set<Int>,
) {
    fun rawOrNull(id: Int): ByteArray? = values[id]

    fun raw(id: Int, name: String): ByteArray =
        values[id] ?: throw MirorLinkProtocolException("Link message is missing required field $name")

    fun varIntOrNull(id: Int): Int? = values[id]?.let { readSingle(it) { r -> r.readVarInt() } }

    fun varInt(id: Int, name: String): Int =
        varIntOrNull(id) ?: throw MirorLinkProtocolException("Link message is missing required field $name")

    fun varLongOrNull(id: Int): Long? = values[id]?.let { readSingle(it) { r -> r.readVarLong() } }

    fun boolOrNull(id: Int): Boolean? = values[id]?.let { readSingle(it) { r -> r.readBool() } }

    fun bool(id: Int, name: String): Boolean =
        boolOrNull(id) ?: throw MirorLinkProtocolException("Link message is missing required field $name")

    fun stringOrNull(id: Int, maxBytes: Int): String? = values[id]?.let { raw ->
        linkRequire(raw.size <= maxBytes) { "Link text field is too long" }
        raw.decodeToString(throwOnInvalidSequence = false)
    }

    fun bytes(id: Int, exactSize: Int, name: String): ByteArray {
        val raw = raw(id, name)
        linkRequire(raw.size == exactSize) { "Link field $name must be exactly $exactSize bytes" }
        return raw
    }

    /**
     * The forward-compatibility gate. Anything this build does not recognise is fine
     * unless the sender marked it critical, in which case continuing would mean
     * silently ignoring something the sender said was load-bearing.
     */
    fun assertNoUnknownCritical(knownIds: Set<Int>) {
        val unknownCritical = criticalIds.firstOrNull { it !in knownIds }
        if (unknownCritical != null) {
            throw MirorLinkUnsupportedException(
                "This Link session needs a newer version of Miror (unknown required field $unknownCritical)"
            )
        }
    }

    private fun <T> readSingle(raw: ByteArray, block: (MirorLinkReader) -> T): T {
        val reader = MirorLinkReader(raw)
        val value = block(reader)
        linkRequire(reader.exhausted) { "Link scalar field has trailing bytes" }
        return value
    }

    companion object {
        fun decode(payload: ByteArray): MirorLinkFieldSet {
            val reader = MirorLinkReader(payload)
            val values = LinkedHashMap<Int, ByteArray>()
            val critical = LinkedHashSet<Int>()
            var count = 0
            while (!reader.exhausted) {
                linkRequire(count < MirorLinkProtocol.MAX_FIELDS_PER_MESSAGE) {
                    "Link message declares too many fields"
                }
                count++
                val id = reader.readVarInt()
                val flags = reader.readByte()
                linkRequire(flags and 0xFE == 0) { "Link field uses reserved flag bits" }
                val body = reader.readLengthPrefixed(MirorLinkProtocol.MAX_PAYLOAD_BYTES)
                linkRequire(values.put(id, body) == null) { "Link message repeats field $id" }
                if (flags and 1 == 1) critical += id
            }
            return MirorLinkFieldSet(values, critical)
        }
    }
}

/** Raised when a peer requires a capability this build does not have. */
internal class MirorLinkUnsupportedException(message: String) : Exception(message)

internal enum class MirorLinkSnapshotScope(val wireValue: Int) {
    NONE(0),
    FULL(1),
    SELECTED(2);

    companion object {
        fun fromWire(value: Int): MirorLinkSnapshotScope =
            entries.firstOrNull { it.wireValue == value }
                ?: throw MirorLinkUnsupportedException(
                    "This Link session needs a newer version of Miror " +
                        "(unknown required snapshot scope $value)",
                )
    }
}

internal enum class MirorLinkResultCode(val wireValue: Int) {
    OK(0),
    DECODE_FAILED(1),
    TOO_LARGE(2),
    DIGEST_MISMATCH(3),
    UNSUPPORTED_FORMAT(4),
    CANCELLED(5),
    TRANSFER_FAILED(6),
    INSTALL_FAILED(7),
    BUSY(8);

    companion object {
        private fun known(value: Int): MirorLinkResultCode? =
            entries.firstOrNull { it.wireValue == value }

        /**
         * Required semantics, for a code carried by a **critical** message.
         *
         * `SNAPSHOT_RESULT` is the collection exchange's own verdict, so a code this
         * build cannot interpret genuinely means it cannot complete the exchange
         * correctly. Asking for an update is the honest outcome.
         */
        fun fromWire(value: Int): MirorLinkResultCode =
            known(value)
                ?: throw MirorLinkUnsupportedException(
                    "This Link session needs a newer version of Miror " +
                        "(unknown required result code $value)",
                )

        /**
         * Tolerant semantics, for a code carried by a **non-critical** message.
         *
         * Content relay and errors are deliberately optional message types precisely so
         * that a version mismatch degrades instead of ending the session -- and the whole
         * point of that design is defeated if an enum *inside* one of them is fatal. A
         * future Miror adding a tenth result code would otherwise kill every older peer's
         * session outright, taking a completed collection exchange down with it.
         *
         * Unknown codes collapse to [TRANSFER_FAILED]: nothing downstream branches on
         * which failure it was, only on whether it succeeded, so a generic failure loses
         * no behaviour. Notably this never reaches `updateRequired` -- an unrecognised
         * number is not evidence that the user needs a new app.
         */
        fun fromWireTolerant(value: Int): MirorLinkResultCode = known(value) ?: TRANSFER_FAILED
    }
}

internal data class MirorLinkContentFile(
    val name: String,
    /** Absent when the donor cannot determine it cheaply -- see the bundle-only case. */
    val sizeBytes: Long?,
)

internal sealed interface MirorLinkMessage {
    val type: Int
    fun encodePayload(): ByteArray
}

internal data class HelloMessage(
    val localNonce: ByteArray,
    val selectedPeerNonce: ByteArray,
    val protocolMin: Int,
    val protocolMax: Int,
    val nickname: String,
    val appBuild: String?,
    val snapshotScope: MirorLinkSnapshotScope,
    val supportsRawManifest: Boolean,
    val contentVersion: Int?,
    val contentServe: Boolean,
    val contentInstall: Boolean,
    /** The peer is willing to serve its own card photos on request. Opt-in, per session. */
    val photoServe: Boolean = false,
) : MirorLinkMessage {
    override val type: Int get() = MirorLinkProtocol.TYPE_HELLO

    override fun encodePayload(): ByteArray = MirorLinkFieldWriter(160).apply {
        putBytes(F_LOCAL_NONCE, true, localNonce)
        putBytes(F_SELECTED_NONCE, true, selectedPeerNonce)
        putVarInt(F_PROTOCOL_MIN, true, protocolMin)
        putVarInt(F_PROTOCOL_MAX, true, protocolMax)
        putBool(F_RAW_MANIFEST, true, supportsRawManifest)
        putVarInt(F_SNAPSHOT_SCOPE, true, snapshotScope.wireValue)
        putString(F_NICKNAME, false, nickname)
        appBuild?.let { putString(F_APP_BUILD, false, it) }
        contentVersion?.let { putVarInt(F_CONTENT_VERSION, false, it) }
        putBool(F_CONTENT_SERVE, false, contentServe)
        putBool(F_CONTENT_INSTALL, false, contentInstall)
        putBool(F_PHOTO_SERVE, false, photoServe)
    }.toByteArray()

    override fun equals(other: Any?): Boolean =
        other is HelloMessage &&
            localNonce.contentEquals(other.localNonce) &&
            selectedPeerNonce.contentEquals(other.selectedPeerNonce) &&
            protocolMin == other.protocolMin && protocolMax == other.protocolMax &&
            nickname == other.nickname && appBuild == other.appBuild &&
            snapshotScope == other.snapshotScope &&
            supportsRawManifest == other.supportsRawManifest &&
            contentVersion == other.contentVersion &&
            contentServe == other.contentServe && contentInstall == other.contentInstall &&
            photoServe == other.photoServe

    override fun hashCode(): Int {
        var result = localNonce.contentHashCode()
        result = 31 * result + selectedPeerNonce.contentHashCode()
        result = 31 * result + protocolMin
        result = 31 * result + protocolMax
        result = 31 * result + nickname.hashCode()
        result = 31 * result + (appBuild?.hashCode() ?: 0)
        result = 31 * result + snapshotScope.hashCode()
        result = 31 * result + supportsRawManifest.hashCode()
        result = 31 * result + (contentVersion ?: 0)
        result = 31 * result + contentServe.hashCode()
        result = 31 * result + contentInstall.hashCode()
        result = 31 * result + photoServe.hashCode()
        return result
    }

    companion object {
        const val F_LOCAL_NONCE = 1
        const val F_SELECTED_NONCE = 2
        const val F_PROTOCOL_MIN = 3
        const val F_PROTOCOL_MAX = 4
        const val F_NICKNAME = 5
        const val F_APP_BUILD = 6
        const val F_SNAPSHOT_SCOPE = 7
        const val F_CONTENT_VERSION = 8
        const val F_CONTENT_SERVE = 9
        const val F_CONTENT_INSTALL = 10
        const val F_RAW_MANIFEST = 11
        const val F_PHOTO_SERVE = 12

        private val KNOWN = setOf(
            F_LOCAL_NONCE, F_SELECTED_NONCE, F_PROTOCOL_MIN, F_PROTOCOL_MAX, F_NICKNAME,
            F_APP_BUILD, F_SNAPSHOT_SCOPE, F_CONTENT_VERSION, F_CONTENT_SERVE,
            F_CONTENT_INSTALL, F_RAW_MANIFEST, F_PHOTO_SERVE,
        )

        fun decode(payload: ByteArray): HelloMessage {
            val fields = MirorLinkFieldSet.decode(payload)
            fields.assertNoUnknownCritical(KNOWN)
            // Bounded on both axes after sanitising, matching what this side is willing
            // to send. The byte bound alone lets a peer occupy far more of the chooser
            // than a local nickname ever could.
            val nickname = fields.stringOrNull(F_NICKNAME, MirorLinkProtocol.MAX_NICKNAME_BYTES)
                ?.let(MirorLinkProtocol::sanitizeNickname)
                ?.let(MirorLinkProtocol::truncateNicknameToBounds)
                .orEmpty()
            return HelloMessage(
                localNonce = fields.bytes(F_LOCAL_NONCE, MirorLinkProtocol.NONCE_BYTES, "localNonce"),
                selectedPeerNonce = fields.bytes(
                    F_SELECTED_NONCE, MirorLinkProtocol.NONCE_BYTES, "selectedPeerNonce",
                ),
                protocolMin = fields.varInt(F_PROTOCOL_MIN, "protocolMin"),
                protocolMax = fields.varInt(F_PROTOCOL_MAX, "protocolMax"),
                nickname = nickname,
                appBuild = fields.stringOrNull(F_APP_BUILD, MirorLinkProtocol.MAX_TEXT_BYTES),
                snapshotScope = MirorLinkSnapshotScope.fromWire(
                    fields.varInt(F_SNAPSHOT_SCOPE, "snapshotScope"),
                ),
                supportsRawManifest = fields.bool(F_RAW_MANIFEST, "supportsRawManifest"),
                contentVersion = fields.varIntOrNull(F_CONTENT_VERSION),
                contentServe = fields.boolOrNull(F_CONTENT_SERVE) ?: false,
                contentInstall = fields.boolOrNull(F_CONTENT_INSTALL) ?: false,
                photoServe = fields.boolOrNull(F_PHOTO_SERVE) ?: false,
            ).also {
                linkRequire(it.protocolMin <= it.protocolMax) { "Link protocol range is inverted" }
            }
        }
    }
}

internal data class SnapshotOfferMessage(
    val present: Boolean,
    val totalBytes: Int,
    val chunkCount: Int,
    val sha256: ByteArray,
    val scope: MirorLinkSnapshotScope,
) : MirorLinkMessage {
    override val type: Int get() = MirorLinkProtocol.TYPE_SNAPSHOT_OFFER

    override fun encodePayload(): ByteArray = MirorLinkFieldWriter(80).apply {
        putBool(F_PRESENT, true, present)
        putVarInt(F_TOTAL_BYTES, true, totalBytes)
        putVarInt(F_CHUNK_COUNT, true, chunkCount)
        putBytes(F_SHA256, true, sha256)
        putVarInt(F_SCOPE, true, scope.wireValue)
    }.toByteArray()

    override fun equals(other: Any?): Boolean =
        other is SnapshotOfferMessage && present == other.present &&
            totalBytes == other.totalBytes && chunkCount == other.chunkCount &&
            sha256.contentEquals(other.sha256) && scope == other.scope

    override fun hashCode(): Int {
        var result = present.hashCode()
        result = 31 * result + totalBytes
        result = 31 * result + chunkCount
        result = 31 * result + sha256.contentHashCode()
        result = 31 * result + scope.hashCode()
        return result
    }

    companion object {
        const val F_PRESENT = 1
        const val F_TOTAL_BYTES = 2
        const val F_CHUNK_COUNT = 3
        const val F_SHA256 = 4
        const val F_SCOPE = 5
        private val KNOWN = setOf(F_PRESENT, F_TOTAL_BYTES, F_CHUNK_COUNT, F_SHA256, F_SCOPE)

        fun decode(payload: ByteArray): SnapshotOfferMessage {
            val fields = MirorLinkFieldSet.decode(payload)
            fields.assertNoUnknownCritical(KNOWN)
            val present = fields.bool(F_PRESENT, "present")
            val totalBytes = fields.varInt(F_TOTAL_BYTES, "totalBytes")
            val chunkCount = fields.varInt(F_CHUNK_COUNT, "chunkCount")
            // Bound the announced total against the shared manifest ceiling before any
            // reassembly buffer is reserved. This is the allocation gate for snapshots.
            linkRequire(totalBytes <= ShareManifestBinaryCodec.MAX_MANIFEST_BYTES) {
                "Offered Link snapshot exceeds the supported manifest size"
            }
            val expectedChunks = if (totalBytes == 0) {
                0
            } else {
                (totalBytes + MirorLinkProtocol.MAX_SNAPSHOT_CHUNK_BYTES - 1) /
                    MirorLinkProtocol.MAX_SNAPSHOT_CHUNK_BYTES
            }
            linkRequire(chunkCount == expectedChunks) {
                "Link snapshot chunk count disagrees with its declared size"
            }
            if (present) {
                linkRequire(totalBytes > 0 && chunkCount > 0) {
                    "A present Link snapshot must contain data"
                }
                linkRequire(
                    fields.varInt(F_SCOPE, "scope") != MirorLinkSnapshotScope.NONE.wireValue,
                ) {
                    "A present Link snapshot must declare a sharing scope"
                }
            } else {
                linkRequire(totalBytes == 0 && chunkCount == 0) {
                    "An absent Link snapshot must declare no bytes"
                }
                linkRequire(
                    fields.varInt(F_SCOPE, "scope") == MirorLinkSnapshotScope.NONE.wireValue,
                ) {
                    "An absent Link snapshot must use the empty scope"
                }
            }
            return SnapshotOfferMessage(
                present = present,
                totalBytes = totalBytes,
                chunkCount = chunkCount,
                sha256 = fields.bytes(F_SHA256, MirorSha256.DIGEST_BYTES, "sha256"),
                scope = MirorLinkSnapshotScope.fromWire(fields.varInt(F_SCOPE, "scope")),
            )
        }
    }
}

internal data class SnapshotChunkMessage(
    val index: Int,
    val data: ByteArray,
) : MirorLinkMessage {
    override val type: Int get() = MirorLinkProtocol.TYPE_SNAPSHOT_CHUNK

    override fun encodePayload(): ByteArray = MirorLinkFieldWriter(data.size + 32).apply {
        putVarInt(F_INDEX, true, index)
        putBytes(F_DATA, true, data)
    }.toByteArray()

    override fun equals(other: Any?): Boolean =
        other is SnapshotChunkMessage && index == other.index && data.contentEquals(other.data)

    override fun hashCode(): Int = 31 * index + data.contentHashCode()

    companion object {
        const val F_INDEX = 1
        const val F_DATA = 2
        private val KNOWN = setOf(F_INDEX, F_DATA)

        fun decode(payload: ByteArray): SnapshotChunkMessage {
            val fields = MirorLinkFieldSet.decode(payload)
            fields.assertNoUnknownCritical(KNOWN)
            val data = fields.raw(F_DATA, "data")
            linkRequire(data.size <= MirorLinkProtocol.MAX_SNAPSHOT_CHUNK_BYTES) {
                "Link snapshot chunk exceeds the per-frame budget"
            }
            return SnapshotChunkMessage(fields.varInt(F_INDEX, "index"), data)
        }
    }
}

internal data class SnapshotResultMessage(
    val code: MirorLinkResultCode,
    val message: String?,
) : MirorLinkMessage {
    override val type: Int get() = MirorLinkProtocol.TYPE_SNAPSHOT_RESULT

    override fun encodePayload(): ByteArray = MirorLinkFieldWriter(48).apply {
        putVarInt(F_CODE, true, code.wireValue)
        message?.let { putString(F_MESSAGE, false, it) }
    }.toByteArray()

    companion object {
        const val F_CODE = 1
        const val F_MESSAGE = 2
        private val KNOWN = setOf(F_CODE, F_MESSAGE)

        fun decode(payload: ByteArray): SnapshotResultMessage {
            val fields = MirorLinkFieldSet.decode(payload)
            fields.assertNoUnknownCritical(KNOWN)
            return SnapshotResultMessage(
                MirorLinkResultCode.fromWire(fields.varInt(F_CODE, "code")),
                fields.stringOrNull(F_MESSAGE, MirorLinkProtocol.MAX_TEXT_BYTES),
            )
        }
    }
}

internal data class ContentOfferMessage(
    val tag: String,
    val version: Int,
    val files: List<MirorLinkContentFile>,
    val totalSizeBytes: Long?,
) : MirorLinkMessage {
    override val type: Int get() = MirorLinkProtocol.TYPE_CONTENT_OFFER

    override fun encodePayload(): ByteArray {
        val fileBlob = MirorLinkWriter(128).apply {
            writeVarInt(files.size)
            files.forEach { file ->
                writeString(file.name)
                writeBool(file.sizeBytes != null)
                writeVarLong(file.sizeBytes ?: 0L)
            }
        }.toByteArray()
        return MirorLinkFieldWriter(fileBlob.size + 64).apply {
            putString(F_TAG, true, tag)
            putVarInt(F_VERSION, true, version)
            putBytes(F_FILES, true, fileBlob)
            totalSizeBytes?.let { putVarLong(F_TOTAL_SIZE, false, it) }
        }.toByteArray()
    }

    companion object {
        const val F_TAG = 1
        const val F_VERSION = 2
        const val F_FILES = 3
        const val F_TOTAL_SIZE = 4
        private val KNOWN = setOf(F_TAG, F_VERSION, F_FILES, F_TOTAL_SIZE)

        fun decode(payload: ByteArray): ContentOfferMessage {
            val fields = MirorLinkFieldSet.decode(payload)
            fields.assertNoUnknownCritical(KNOWN)
            val reader = MirorLinkReader(fields.raw(F_FILES, "files"))
            val count = reader.readVarInt()
            linkRequire(count in 1..MirorLinkProtocol.MAX_CONTENT_FILES) {
                "Link content offer declares an unsupported file count"
            }
            val files = ArrayList<MirorLinkContentFile>(count)
            repeat(count) {
                val name = reader.readString(MirorLinkProtocol.MAX_FILE_NAME_BYTES)
                val hasSize = reader.readBool()
                val size = reader.readVarLong()
                files += MirorLinkContentFile(name, if (hasSize) size else null)
            }
            linkRequire(reader.exhausted) { "Link content offer has trailing file bytes" }
            return ContentOfferMessage(
                tag = fields.stringOrNull(F_TAG, MirorLinkProtocol.MAX_TEXT_BYTES)
                    ?: throw MirorLinkProtocolException("Link content offer is missing its tag"),
                version = fields.varInt(F_VERSION, "version"),
                files = files,
                totalSizeBytes = fields.varLongOrNull(F_TOTAL_SIZE),
            )
        }
    }
}

internal data object ContentAcceptMessage : MirorLinkMessage {
    override val type: Int get() = MirorLinkProtocol.TYPE_CONTENT_ACCEPT
    override fun encodePayload(): ByteArray = ByteArray(0)
}

internal data object ContentDeclineMessage : MirorLinkMessage {
    override val type: Int get() = MirorLinkProtocol.TYPE_CONTENT_DECLINE
    override fun encodePayload(): ByteArray = ByteArray(0)
}

internal data class ContentFileBeginMessage(
    val fileIndex: Int,
    val name: String,
    val sizeBytes: Long,
) : MirorLinkMessage {
    override val type: Int get() = MirorLinkProtocol.TYPE_CONTENT_FILE_BEGIN

    override fun encodePayload(): ByteArray = MirorLinkFieldWriter(64).apply {
        putVarInt(F_INDEX, true, fileIndex)
        putString(F_NAME, true, name)
        putVarLong(F_SIZE, true, sizeBytes)
    }.toByteArray()

    companion object {
        const val F_INDEX = 1
        const val F_NAME = 2
        const val F_SIZE = 3
        private val KNOWN = setOf(F_INDEX, F_NAME, F_SIZE)

        fun decode(payload: ByteArray): ContentFileBeginMessage {
            val fields = MirorLinkFieldSet.decode(payload)
            fields.assertNoUnknownCritical(KNOWN)
            return ContentFileBeginMessage(
                fileIndex = fields.varInt(F_INDEX, "fileIndex"),
                name = fields.stringOrNull(F_NAME, MirorLinkProtocol.MAX_FILE_NAME_BYTES)
                    ?: throw MirorLinkProtocolException("Link content file has no name"),
                sizeBytes = fields.varLongOrNull(F_SIZE)
                    ?: throw MirorLinkProtocolException("Link content file has no size"),
            )
        }
    }
}

internal data class ContentChunkMessage(
    val fileIndex: Int,
    val sequence: Int,
    val data: ByteArray,
) : MirorLinkMessage {
    override val type: Int get() = MirorLinkProtocol.TYPE_CONTENT_CHUNK

    override fun encodePayload(): ByteArray = MirorLinkFieldWriter(data.size + 32).apply {
        putVarInt(F_FILE_INDEX, true, fileIndex)
        putVarInt(F_SEQUENCE, true, sequence)
        putBytes(F_DATA, true, data)
    }.toByteArray()

    override fun equals(other: Any?): Boolean =
        other is ContentChunkMessage && fileIndex == other.fileIndex &&
            sequence == other.sequence && data.contentEquals(other.data)

    override fun hashCode(): Int {
        var result = fileIndex
        result = 31 * result + sequence
        result = 31 * result + data.contentHashCode()
        return result
    }

    companion object {
        const val F_FILE_INDEX = 1
        const val F_SEQUENCE = 2
        const val F_DATA = 3
        private val KNOWN = setOf(F_FILE_INDEX, F_SEQUENCE, F_DATA)

        fun decode(payload: ByteArray): ContentChunkMessage {
            val fields = MirorLinkFieldSet.decode(payload)
            fields.assertNoUnknownCritical(KNOWN)
            val data = fields.raw(F_DATA, "data")
            linkRequire(data.size <= MirorLinkProtocol.MAX_CONTENT_CHUNK_BYTES) {
                "Link content chunk exceeds the per-frame budget"
            }
            return ContentChunkMessage(
                fields.varInt(F_FILE_INDEX, "fileIndex"),
                fields.varInt(F_SEQUENCE, "sequence"),
                data,
            )
        }
    }
}

internal data class ContentFileEndMessage(
    val fileIndex: Int,
    val totalBytes: Long,
    val sha256: ByteArray,
) : MirorLinkMessage {
    override val type: Int get() = MirorLinkProtocol.TYPE_CONTENT_FILE_END

    override fun encodePayload(): ByteArray = MirorLinkFieldWriter(64).apply {
        putVarInt(F_INDEX, true, fileIndex)
        putVarLong(F_TOTAL, true, totalBytes)
        putBytes(F_SHA256, true, sha256)
    }.toByteArray()

    override fun equals(other: Any?): Boolean =
        other is ContentFileEndMessage && fileIndex == other.fileIndex &&
            totalBytes == other.totalBytes && sha256.contentEquals(other.sha256)

    override fun hashCode(): Int {
        var result = fileIndex
        result = 31 * result + totalBytes.hashCode()
        result = 31 * result + sha256.contentHashCode()
        return result
    }

    companion object {
        const val F_INDEX = 1
        const val F_TOTAL = 2
        const val F_SHA256 = 3
        private val KNOWN = setOf(F_INDEX, F_TOTAL, F_SHA256)

        fun decode(payload: ByteArray): ContentFileEndMessage {
            val fields = MirorLinkFieldSet.decode(payload)
            fields.assertNoUnknownCritical(KNOWN)
            return ContentFileEndMessage(
                fields.varInt(F_INDEX, "fileIndex"),
                fields.varLongOrNull(F_TOTAL)
                    ?: throw MirorLinkProtocolException("Link content file end has no length"),
                fields.bytes(F_SHA256, MirorSha256.DIGEST_BYTES, "sha256"),
            )
        }
    }
}

internal data class ContentResultMessage(
    val code: MirorLinkResultCode,
    val message: String?,
) : MirorLinkMessage {
    override val type: Int get() = MirorLinkProtocol.TYPE_CONTENT_RESULT

    override fun encodePayload(): ByteArray = MirorLinkFieldWriter(48).apply {
        putVarInt(F_CODE, true, code.wireValue)
        message?.let { putString(F_MESSAGE, false, it) }
    }.toByteArray()

    companion object {
        const val F_CODE = 1
        const val F_MESSAGE = 2
        private val KNOWN = setOf(F_CODE, F_MESSAGE)

        // Tolerant: TYPE_CONTENT_RESULT is a non-critical type, so an unknown code must
        // become a generic content failure rather than ending a session whose collection
        // exchange may already have completed.
        fun decode(payload: ByteArray): ContentResultMessage {
            val fields = MirorLinkFieldSet.decode(payload)
            fields.assertNoUnknownCritical(KNOWN)
            return ContentResultMessage(
                MirorLinkResultCode.fromWireTolerant(fields.varInt(F_CODE, "code")),
                fields.stringOrNull(F_MESSAGE, MirorLinkProtocol.MAX_TEXT_BYTES),
            )
        }
    }
}

internal data class CancelMessage(val reason: String?) : MirorLinkMessage {
    override val type: Int get() = MirorLinkProtocol.TYPE_CANCEL

    override fun encodePayload(): ByteArray = MirorLinkFieldWriter(48).apply {
        reason?.let { putString(F_REASON, false, it) }
    }.toByteArray()

    companion object {
        const val F_REASON = 1
        private val KNOWN = setOf(F_REASON)

        fun decode(payload: ByteArray): CancelMessage {
            val fields = MirorLinkFieldSet.decode(payload)
            fields.assertNoUnknownCritical(KNOWN)
            return CancelMessage(fields.stringOrNull(F_REASON, MirorLinkProtocol.MAX_TEXT_BYTES))
        }
    }
}

internal data object CompleteMessage : MirorLinkMessage {
    override val type: Int get() = MirorLinkProtocol.TYPE_COMPLETE
    override fun encodePayload(): ByteArray = ByteArray(0)
}

internal data class ErrorMessage(
    val code: MirorLinkResultCode,
    val message: String?,
) : MirorLinkMessage {
    override val type: Int get() = MirorLinkProtocol.TYPE_ERROR

    override fun encodePayload(): ByteArray = MirorLinkFieldWriter(48).apply {
        putVarInt(F_CODE, true, code.wireValue)
        message?.let { putString(F_MESSAGE, false, it) }
    }.toByteArray()

    companion object {
        const val F_CODE = 1
        const val F_MESSAGE = 2
        private val KNOWN = setOf(F_CODE, F_MESSAGE)

        // Tolerant, for the same reason as ContentResultMessage. An ERROR frame keeps its
        // generic "the peer reported a problem" meaning either way; what it must not do is
        // tell the user to update Miror merely because a number was unfamiliar.
        fun decode(payload: ByteArray): ErrorMessage {
            val fields = MirorLinkFieldSet.decode(payload)
            fields.assertNoUnknownCritical(KNOWN)
            return ErrorMessage(
                MirorLinkResultCode.fromWireTolerant(fields.varInt(F_CODE, "code")),
                fields.stringOrNull(F_MESSAGE, MirorLinkProtocol.MAX_TEXT_BYTES),
            )
        }
    }
}

internal data class PhotoRequestMessage(val cardId: String) : MirorLinkMessage {
    override val type: Int get() = MirorLinkProtocol.TYPE_PHOTO_REQUEST

    override fun encodePayload(): ByteArray = MirorLinkFieldWriter(48).apply {
        putString(F_CARD_ID, true, cardId)
    }.toByteArray()

    companion object {
        const val F_CARD_ID = 1
        private val KNOWN = setOf(F_CARD_ID)

        fun decode(payload: ByteArray): PhotoRequestMessage {
            val fields = MirorLinkFieldSet.decode(payload)
            fields.assertNoUnknownCritical(KNOWN)
            return PhotoRequestMessage(
                fields.stringOrNull(F_CARD_ID, MirorLinkProtocol.MAX_CARD_ID_BYTES)
                    ?: throw MirorLinkProtocolException("Photo request has no card id"),
            )
        }
    }
}

internal data class PhotoResponseMessage(
    val cardId: String,
    val available: Boolean,
    val totalBytes: Int,
    val chunkCount: Int,
    val sha256: ByteArray,
) : MirorLinkMessage {
    override val type: Int get() = MirorLinkProtocol.TYPE_PHOTO_RESPONSE

    override fun encodePayload(): ByteArray = MirorLinkFieldWriter(80).apply {
        putString(F_CARD_ID, true, cardId)
        putBool(F_AVAILABLE, true, available)
        putVarInt(F_TOTAL_BYTES, true, totalBytes)
        putVarInt(F_CHUNK_COUNT, true, chunkCount)
        putBytes(F_SHA256, true, sha256)
    }.toByteArray()

    override fun equals(other: Any?): Boolean =
        other is PhotoResponseMessage && cardId == other.cardId &&
            available == other.available && totalBytes == other.totalBytes &&
            chunkCount == other.chunkCount && sha256.contentEquals(other.sha256)

    override fun hashCode(): Int {
        var result = cardId.hashCode()
        result = 31 * result + available.hashCode()
        result = 31 * result + totalBytes
        result = 31 * result + chunkCount
        result = 31 * result + sha256.contentHashCode()
        return result
    }

    companion object {
        const val F_CARD_ID = 1
        const val F_AVAILABLE = 2
        const val F_TOTAL_BYTES = 3
        const val F_CHUNK_COUNT = 4
        const val F_SHA256 = 5
        private val KNOWN = setOf(F_CARD_ID, F_AVAILABLE, F_TOTAL_BYTES, F_CHUNK_COUNT, F_SHA256)

        fun decode(payload: ByteArray): PhotoResponseMessage {
            val fields = MirorLinkFieldSet.decode(payload)
            fields.assertNoUnknownCritical(KNOWN)
            val total = fields.varInt(F_TOTAL_BYTES, "totalBytes")
            val chunks = fields.varInt(F_CHUNK_COUNT, "chunkCount")
            // The allocation gate: a peer cannot make the viewer reserve an arbitrary
            // buffer by claiming a huge photo.
            linkRequire(total <= MirorLinkProtocol.MAX_PHOTO_BYTES) {
                "Offered photo exceeds the supported size"
            }
            // Photo chunks use their own, smaller budget: a photo frame also carries the
            // card id, whose bound is not the sender's to choose.
            val expectedChunks = if (total == 0) {
                0
            } else {
                (total + MirorLinkProtocol.MAX_PHOTO_CHUNK_BYTES - 1) /
                    MirorLinkProtocol.MAX_PHOTO_CHUNK_BYTES
            }
            linkRequire(chunks == expectedChunks) { "Photo chunk count disagrees with its size" }
            val available = fields.bool(F_AVAILABLE, "available")
            if (available) {
                linkRequire(total > 0 && chunks > 0) {
                    "An available photo must contain data"
                }
            } else {
                linkRequire(total == 0 && chunks == 0) {
                    "An unavailable photo must declare no data"
                }
            }
            return PhotoResponseMessage(
                cardId = fields.stringOrNull(F_CARD_ID, MirorLinkProtocol.MAX_CARD_ID_BYTES)
                    ?: throw MirorLinkProtocolException("Photo response has no card id"),
                available = available,
                totalBytes = total,
                chunkCount = chunks,
                sha256 = fields.bytes(F_SHA256, MirorSha256.DIGEST_BYTES, "sha256"),
            )
        }
    }
}

internal data class PhotoChunkMessage(
    val cardId: String,
    val index: Int,
    val data: ByteArray,
) : MirorLinkMessage {
    override val type: Int get() = MirorLinkProtocol.TYPE_PHOTO_CHUNK

    override fun encodePayload(): ByteArray = MirorLinkFieldWriter(data.size + 48).apply {
        putString(F_CARD_ID, true, cardId)
        putVarInt(F_INDEX, true, index)
        putBytes(F_DATA, true, data)
    }.toByteArray()

    override fun equals(other: Any?): Boolean =
        other is PhotoChunkMessage && cardId == other.cardId &&
            index == other.index && data.contentEquals(other.data)

    override fun hashCode(): Int {
        var result = cardId.hashCode()
        result = 31 * result + index
        result = 31 * result + data.contentHashCode()
        return result
    }

    companion object {
        const val F_CARD_ID = 1
        const val F_INDEX = 2
        const val F_DATA = 3
        private val KNOWN = setOf(F_CARD_ID, F_INDEX, F_DATA)

        fun decode(payload: ByteArray): PhotoChunkMessage {
            val fields = MirorLinkFieldSet.decode(payload)
            fields.assertNoUnknownCritical(KNOWN)
            val data = fields.raw(F_DATA, "data")
            linkRequire(data.size <= MirorLinkProtocol.MAX_PHOTO_CHUNK_BYTES) {
                "Photo chunk exceeds the per-frame budget"
            }
            return PhotoChunkMessage(
                cardId = fields.stringOrNull(F_CARD_ID, MirorLinkProtocol.MAX_CARD_ID_BYTES)
                    ?: throw MirorLinkProtocolException("Photo chunk has no card id"),
                index = fields.varInt(F_INDEX, "index"),
                data = data,
            )
        }
    }
}

internal object MirorLinkCodec {
    fun encode(sessionId: ByteArray, messageId: Int, message: MirorLinkMessage): ByteArray =
        MirorLinkProtocol.encodeFrame(
            MirorLinkFrame(
                protocolMajor = MirorLinkProtocol.PROTOCOL_MAJOR,
                protocolMinor = MirorLinkProtocol.PROTOCOL_MINOR,
                type = message.type,
                critical = MirorLinkProtocol.isCriticalType(message.type),
                sessionId = sessionId,
                messageId = messageId,
                payload = message.encodePayload(),
            ),
        )

    /**
     * Returns null when the frame carries a message this build does not know but the
     * sender marked skippable. Throws [MirorLinkUnsupportedException] when the sender
     * marked it required, and [MirorLinkProtocolException] when the bytes are malformed.
     */
    fun decode(frame: MirorLinkFrame): MirorLinkMessage? {
        if (frame.protocolMajor != MirorLinkProtocol.PROTOCOL_MAJOR) {
            throw MirorLinkUnsupportedException(
                "This Link session needs a newer version of Miror " +
                    "(unsupported protocol ${frame.protocolMajor}.${frame.protocolMinor})",
            )
        }
        if (!MirorLinkProtocol.isKnownType(frame.type)) {
            if (frame.critical) {
                throw MirorLinkUnsupportedException(
                    "This Link session needs a newer version of Miror (unknown required message ${frame.type})"
                )
            }
            return null
        }
        return when (frame.type) {
            MirorLinkProtocol.TYPE_HELLO -> HelloMessage.decode(frame.payload)
            MirorLinkProtocol.TYPE_SNAPSHOT_OFFER -> SnapshotOfferMessage.decode(frame.payload)
            MirorLinkProtocol.TYPE_SNAPSHOT_CHUNK -> SnapshotChunkMessage.decode(frame.payload)
            MirorLinkProtocol.TYPE_SNAPSHOT_RESULT -> SnapshotResultMessage.decode(frame.payload)
            MirorLinkProtocol.TYPE_CONTENT_OFFER -> ContentOfferMessage.decode(frame.payload)
            MirorLinkProtocol.TYPE_CONTENT_ACCEPT ->
                decodeMarker(frame.payload, ContentAcceptMessage)
            MirorLinkProtocol.TYPE_CONTENT_DECLINE ->
                decodeMarker(frame.payload, ContentDeclineMessage)
            MirorLinkProtocol.TYPE_CONTENT_FILE_BEGIN -> ContentFileBeginMessage.decode(frame.payload)
            MirorLinkProtocol.TYPE_CONTENT_CHUNK -> ContentChunkMessage.decode(frame.payload)
            MirorLinkProtocol.TYPE_CONTENT_FILE_END -> ContentFileEndMessage.decode(frame.payload)
            MirorLinkProtocol.TYPE_CONTENT_RESULT -> ContentResultMessage.decode(frame.payload)
            MirorLinkProtocol.TYPE_PHOTO_REQUEST -> PhotoRequestMessage.decode(frame.payload)
            MirorLinkProtocol.TYPE_PHOTO_RESPONSE -> PhotoResponseMessage.decode(frame.payload)
            MirorLinkProtocol.TYPE_PHOTO_CHUNK -> PhotoChunkMessage.decode(frame.payload)
            MirorLinkProtocol.TYPE_CANCEL -> CancelMessage.decode(frame.payload)
            MirorLinkProtocol.TYPE_COMPLETE -> decodeMarker(frame.payload, CompleteMessage)
            MirorLinkProtocol.TYPE_ERROR -> ErrorMessage.decode(frame.payload)
            else -> null
        }
    }

    /**
     * Marker messages currently have no fields, but they still use the extensible field
     * envelope. A future sender may add an optional field (safe to skip) or a critical
     * one (this build must ask for an update). Returning the singleton without parsing
     * its payload silently accepted both, bypassing the protocol's compatibility rule.
     */
    private fun decodeMarker(
        payload: ByteArray,
        message: MirorLinkMessage,
    ): MirorLinkMessage {
        val fields = MirorLinkFieldSet.decode(payload)
        fields.assertNoUnknownCritical(emptySet())
        return message
    }
}
