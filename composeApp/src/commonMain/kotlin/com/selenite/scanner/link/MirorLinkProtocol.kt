package com.selenite.scanner.link

/**
 * The `MLNK` wire protocol.
 *
 * Two independent compatibility mechanisms, matching the plan:
 *
 * 1. A supported protocol *range* in `HELLO`, so no per-version compatibility
 *    table has to be maintained anywhere.
 * 2. Length-delimited fields and messages carrying an explicit critical flag.
 *    Unknown *optional* things are skipped by length; unknown *critical* things
 *    fail loudly with an update-required result rather than being guessed at.
 *
 * Content messages are deliberately non-critical, so a peer that predates peer
 * content relay entirely still completes a collection exchange with a peer that
 * has it.
 */
internal object MirorLinkProtocol {
    val MAGIC = byteArrayOf('M'.code.toByte(), 'L'.code.toByte(), 'N'.code.toByte(), 'K'.code.toByte())

    const val PROTOCOL_MAJOR = 1
    const val PROTOCOL_MINOR = 0

    /** Inclusive range this build can speak. Negotiated in `HELLO`. */
    const val SUPPORTED_PROTOCOL_MIN = 1
    const val SUPPORTED_PROTOCOL_MAX = 1

    const val SESSION_ID_BYTES = 16
    const val NONCE_BYTES = 16
    const val HEADER_BYTES = 32

    /**
     * The cap is on the **complete encoded frame**, header included -- not on the
     * payload with an unbounded header on top. Nearby's byte-payload ceiling is
     * larger than this on both platforms; staying conservative keeps one number
     * true everywhere instead of tracking each SDK's limit.
     */
    const val MAX_FRAME_BYTES = 24 * 1024
    const val MAX_PAYLOAD_BYTES = MAX_FRAME_BYTES - HEADER_BYTES

    const val MAX_FIELDS_PER_MESSAGE = 64
    const val MAX_NICKNAME_BYTES = 64
    const val MAX_NICKNAME_CODE_POINTS = 24
    const val MAX_TEXT_BYTES = 256
    const val MAX_CONTENT_FILES = 8
    const val MAX_FILE_NAME_BYTES = 128

    /**
     * Miror content releases are the same fixed trio used by the network updater.
     * Keeping the names in the protocol contract prevents a peer from describing a
     * different file set that the platform installer cannot safely consume.
     */
    val CONTENT_FILE_NAMES = listOf(
        "cards.db",
        "master_index_v3.bin",
        "master_index_v3_ids.txt",
    )

    /**
     * Receiver-side disk budgets. Current releases are roughly 57 MiB; these leave
     * generous growth room while ensuring a corrupt peer cannot stream indefinitely
     * into app-private storage.
     */
    const val MAX_CONTENT_FILE_BYTES = 192L * 1024L * 1024L
    const val MAX_CONTENT_PACKAGE_BYTES = 256L * 1024L * 1024L

    // Message types. Values are frozen; new types append.
    const val TYPE_HELLO = 1
    const val TYPE_SNAPSHOT_OFFER = 2
    const val TYPE_SNAPSHOT_CHUNK = 3
    const val TYPE_SNAPSHOT_RESULT = 4
    const val TYPE_CONTENT_OFFER = 5
    const val TYPE_CONTENT_ACCEPT = 6
    const val TYPE_CONTENT_DECLINE = 7
    const val TYPE_CONTENT_FILE_BEGIN = 8
    const val TYPE_CONTENT_CHUNK = 9
    const val TYPE_CONTENT_FILE_END = 10
    const val TYPE_CONTENT_RESULT = 11
    const val TYPE_CANCEL = 12
    const val TYPE_COMPLETE = 13
    const val TYPE_ERROR = 14

    // Photo relay. Non-critical: a peer that predates it simply never answers, and the
    // viewer falls back to the catalog image exactly as it does today.
    const val TYPE_PHOTO_REQUEST = 15
    const val TYPE_PHOTO_RESPONSE = 16
    const val TYPE_PHOTO_CHUNK = 17

    /**
     * Types whose absence from a peer's vocabulary must abort the session. Content
     * relay is intentionally absent: an older peer skipping it still shares cards.
     */
    private val CRITICAL_TYPES = setOf(
        TYPE_HELLO,
        TYPE_SNAPSHOT_OFFER,
        TYPE_SNAPSHOT_CHUNK,
        TYPE_SNAPSHOT_RESULT,
        TYPE_CANCEL,
        TYPE_COMPLETE,
    )

    private val KNOWN_TYPES = setOf(
        TYPE_HELLO, TYPE_SNAPSHOT_OFFER, TYPE_SNAPSHOT_CHUNK, TYPE_SNAPSHOT_RESULT,
        TYPE_CONTENT_OFFER, TYPE_CONTENT_ACCEPT, TYPE_CONTENT_DECLINE,
        TYPE_CONTENT_FILE_BEGIN, TYPE_CONTENT_CHUNK, TYPE_CONTENT_FILE_END,
        TYPE_CONTENT_RESULT, TYPE_CANCEL, TYPE_COMPLETE, TYPE_ERROR,
        TYPE_PHOTO_REQUEST, TYPE_PHOTO_RESPONSE, TYPE_PHOTO_CHUNK,
    )

    /**
     * Photos are fetched one card at a time, on tap, and are downscaled before sending.
     * A whole collection's originals would be hundreds of megabytes; this keeps a single
     * card's transfer to a fraction of a second and means a viewer that never opens a card
     * costs nothing at all.
     */
    const val MAX_PHOTO_BYTES = 512 * 1024
    const val MAX_PHOTO_CACHE_ENTRIES = 64
    const val MAX_CARD_ID_BYTES = 128

    fun isCriticalType(type: Int): Boolean = type in CRITICAL_TYPES

    fun isKnownType(type: Int): Boolean = type in KNOWN_TYPES

    /** Largest snapshot chunk body that still fits one frame after field overhead. */
    val MAX_SNAPSHOT_CHUNK_BYTES: Int = MAX_PAYLOAD_BYTES - 64

    /** Largest content chunk body that still fits one frame after field overhead. */
    val MAX_CONTENT_CHUNK_BYTES: Int = MAX_PAYLOAD_BYTES - 64

    /**
     * Envelope reserved by a photo frame, which is not the same as a snapshot frame's.
     *
     * A `PHOTO_CHUNK` carries the card id alongside its data, and that id is bounded by
     * [MAX_CARD_ID_BYTES] rather than by anything the sender picks. Sizing photo chunks
     * with the snapshot budget reserved only 64 bytes for the whole envelope, so a legal
     * long id plus a full-size chunk overran [MAX_PAYLOAD_BYTES] and made `encodeFrame`
     * throw -- from a coroutine started by an inbound frame.
     *
     * Derived from the id bound rather than guessed at. The worst case is:
     *
     *   card id field   1 id + 1 flags + 2 length + MAX_CARD_ID_BYTES  = 132
     *   index field     1 id + 1 flags + 1 length + 5 varint           =   8
     *   data field      1 id + 1 flags + 3 length                      =   5
     *                                                            total = 145
     *
     * 192 leaves room for the id bound to grow to 175 bytes before this needs revisiting.
     */
    const val PHOTO_FRAME_OVERHEAD_BYTES = 192

    /** Largest photo chunk body that still fits one frame, worst-case card id included. */
    val MAX_PHOTO_CHUNK_BYTES: Int = MAX_PAYLOAD_BYTES - PHOTO_FRAME_OVERHEAD_BYTES

    fun encodeFrame(frame: MirorLinkFrame): ByteArray {
        require(frame.sessionId.size == SESSION_ID_BYTES) { "Link session id must be 16 bytes" }
        require(frame.payload.size <= MAX_PAYLOAD_BYTES) {
            "Link payload of ${frame.payload.size} bytes exceeds the $MAX_PAYLOAD_BYTES byte budget"
        }
        val writer = MirorLinkWriter(HEADER_BYTES + frame.payload.size)
        writer.writeBytes(MAGIC)
        writer.writeByte(frame.protocolMajor)
        writer.writeByte(frame.protocolMinor)
        writer.writeByte(frame.type)
        writer.writeByte(if (frame.critical) 1 else 0)
        writer.writeBytes(frame.sessionId)
        writer.writeIntLe(frame.messageId)
        writer.writeIntLe(frame.payload.size)
        writer.writeBytes(frame.payload)
        val encoded = writer.toByteArray()
        check(encoded.size <= MAX_FRAME_BYTES) { "Encoded Link frame exceeds the frame budget" }
        return encoded
    }

    /**
     * Validates every fixed header field and the declared payload length against the
     * bytes actually received before any payload allocation happens.
     */
    fun decodeFrame(bytes: ByteArray): MirorLinkFrame {
        linkRequire(bytes.size <= MAX_FRAME_BYTES) { "Link frame exceeds the frame budget" }
        linkRequire(bytes.size >= HEADER_BYTES) { "Link frame is shorter than its header" }
        val reader = MirorLinkReader(bytes)
        val magic = reader.readBytes(4)
        linkRequire(magic.contentEquals(MAGIC)) { "Not a Miror Link frame" }
        val major = reader.readByte()
        val minor = reader.readByte()
        val type = reader.readByte()
        val flags = reader.readByte()
        linkRequire(flags and 0xFE == 0) { "Link frame uses reserved flag bits" }
        val sessionId = reader.readBytes(SESSION_ID_BYTES)
        val messageId = reader.readIntLe()
        linkRequire(messageId >= 0) { "Link message id must be non-negative" }
        val declared = reader.readIntLe()
        linkRequire(declared in 0..MAX_PAYLOAD_BYTES) { "Link payload length is out of range" }
        linkRequire(declared == reader.remaining) { "Link payload length disagrees with the frame" }
        val payload = reader.readBytes(declared)
        return MirorLinkFrame(
            protocolMajor = major,
            protocolMinor = minor,
            type = type,
            critical = (flags and 1) == 1,
            sessionId = sessionId,
            messageId = messageId,
            payload = payload,
        )
    }

    fun protocolRangesOverlap(peerMin: Int, peerMax: Int): Boolean =
        peerMin <= SUPPORTED_PROTOCOL_MAX && peerMax >= SUPPORTED_PROTOCOL_MIN

    fun canonicalContentTag(version: Int): String = "content-v$version"

    fun isCanonicalContentTag(tag: String, version: Int): Boolean =
        version > 0 && tag == canonicalContentTag(version)

    /**
     * Nickname bound applied on both the grapheme-ish and byte axes, per the plan.
     * Code points stand in for graphemes: common Kotlin has no grapheme breaker, and
     * the bound only needs to stop abusive input, not to be typographically exact.
     */
    fun isNicknameWithinBounds(nickname: String): Boolean {
        val bytes = nickname.encodeToByteArray()
        if (bytes.size > MAX_NICKNAME_BYTES) return false
        var codePoints = 0
        var index = 0
        while (index < nickname.length) {
            val char = nickname[index]
            index += if (char.isHighSurrogate() && index + 1 < nickname.length &&
                nickname[index + 1].isLowSurrogate()
            ) 2 else 1
            codePoints++
            if (codePoints > MAX_NICKNAME_CODE_POINTS) return false
        }
        return true
    }

    /**
     * Strips control characters and collapses whitespace. Unicode and emoji survive;
     * bidi/format characters that could make one nickname impersonate another do not.
     */
    /**
     * Clamps a peer-supplied nickname to the same bounds a local one must satisfy.
     *
     * [isNicknameWithinBounds] is enforced on the way out but was never applied on the
     * way in, so a peer could advertise a name far longer in code points than any local
     * user can choose -- crowding the chooser that exists to tell peers apart. Truncated
     * rather than rejected: a long name is untidy, not hostile, and dropping the peer
     * entirely would be a worse outcome than showing a shortened name.
     */
    fun truncateNicknameToBounds(nickname: String): String {
        if (isNicknameWithinBounds(nickname)) return nickname
        val builder = StringBuilder()
        var codePoints = 0
        var index = 0
        while (index < nickname.length && codePoints < MAX_NICKNAME_CODE_POINTS) {
            val char = nickname[index]
            val isPair = char.isHighSurrogate() &&
                index + 1 < nickname.length &&
                nickname[index + 1].isLowSurrogate()
            val width = if (isPair) 2 else 1
            val candidate = nickname.substring(index, index + width)
            if (builder.length + candidate.length > MAX_NICKNAME_BYTES) break
            if ((builder.toString() + candidate).encodeToByteArray().size > MAX_NICKNAME_BYTES) break
            builder.append(candidate)
            codePoints++
            index += width
        }
        return builder.toString().trim()
    }

    fun sanitizeNickname(raw: String): String {
        val filtered = buildString(raw.length) {
            var index = 0
            while (index < raw.length) {
                val char = raw[index]
                // Iterated by code point, not by UTF-16 unit, because the Tags block
                // below is supplementary: scanning Chars alone only ever sees its
                // surrogate halves and would let every tag character through.
                val isSurrogatePair = char.isHighSurrogate() &&
                    index + 1 < raw.length &&
                    raw[index + 1].isLowSurrogate()
                val code = if (isSurrogatePair) {
                    0x10000 + ((char.code - 0xD800) shl 10) + (raw[index + 1].code - 0xDC00)
                } else {
                    char.code
                }
                val width = if (isSurrogatePair) 2 else 1

                val isControl = code < 0x20 || code == 0x7F ||
                    (code in 0x80..0x9F) ||
                    (code in 0x200B..0x200F) ||
                    (code in 0x202A..0x202E) ||
                    (code in 0x2066..0x2069) ||
                    // Line/paragraph separators and the remaining directional marks.
                    // These matter because the chooser is how a user picks their friend:
                    // a name that renders as another name is straightforward
                    // impersonation. Ordinary text, emoji and every script pass through
                    // untouched -- this is anti-spoofing, not a content filter.
                    code == 0x2028 || code == 0x2029 ||
                    code == 0x061C || code == 0x180E ||
                    code == 0xFEFF ||
                    // Unicode Tags: invisible, and historically a way to smuggle text
                    // into a name that renders as something else entirely.
                    (code in 0xE0000..0xE007F) ||
                    // Unpaired surrogates are not characters at all.
                    (!isSurrogatePair && char.isSurrogate())
                if (!isControl) {
                    append(char)
                    if (isSurrogatePair) append(raw[index + 1])
                }
                index += width
            }
        }
        return filtered.split(' ').filter { it.isNotEmpty() }.joinToString(" ").trim()
    }
}

internal data class MirorLinkFrame(
    val protocolMajor: Int,
    val protocolMinor: Int,
    val type: Int,
    val critical: Boolean,
    val sessionId: ByteArray,
    val messageId: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is MirorLinkFrame &&
            protocolMajor == other.protocolMajor &&
            protocolMinor == other.protocolMinor &&
            type == other.type &&
            critical == other.critical &&
            sessionId.contentEquals(other.sessionId) &&
            messageId == other.messageId &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var result = protocolMajor
        result = 31 * result + protocolMinor
        result = 31 * result + type
        result = 31 * result + critical.hashCode()
        result = 31 * result + sessionId.contentHashCode()
        result = 31 * result + messageId
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
