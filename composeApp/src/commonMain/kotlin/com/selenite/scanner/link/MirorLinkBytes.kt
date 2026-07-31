package com.selenite.scanner.link

/**
 * Raised for any structurally invalid Link input.
 *
 * Every decode path in this package converts malformed peer data into this
 * exception rather than letting an arbitrary runtime exception escape, so the
 * coordinator has exactly one failure shape to handle and can never be induced to
 * allocate from an unvalidated length.
 */
internal class MirorLinkProtocolException(message: String) : Exception(message)

internal fun linkRequire(condition: Boolean, message: () -> String) {
    if (!condition) throw MirorLinkProtocolException(message())
}

/** Growable little-endian writer. Frames are small and bounded, so this stays simple. */
internal class MirorLinkWriter(initialCapacity: Int = 64) {
    private var buffer = ByteArray(initialCapacity.coerceAtLeast(16))
    private var length = 0

    val size: Int get() = length

    private fun ensure(extra: Int) {
        val required = length + extra
        if (required <= buffer.size) return
        var capacity = buffer.size
        while (capacity < required) capacity = capacity shl 1
        buffer = buffer.copyOf(capacity)
    }

    fun writeByte(value: Int) {
        ensure(1)
        buffer[length++] = (value and 0xFF).toByte()
    }

    fun writeBool(value: Boolean) = writeByte(if (value) 1 else 0)

    fun writeBytes(value: ByteArray, offset: Int = 0, count: Int = value.size - offset) {
        ensure(count)
        value.copyInto(buffer, length, offset, offset + count)
        length += count
    }

    fun writeIntLe(value: Int) {
        ensure(4)
        buffer[length++] = value.toByte()
        buffer[length++] = (value ushr 8).toByte()
        buffer[length++] = (value ushr 16).toByte()
        buffer[length++] = (value ushr 24).toByte()
    }

    /**
     * Canonical unsigned LEB128. Canonical because a non-minimal encoding would let
     * the same logical frame have several byte spellings, which would break the
     * golden vectors and give an attacker free variation under a digest.
     */
    fun writeVarInt(value: Int) {
        require(value >= 0) { "Link varint must be non-negative" }
        var current = value
        while (current >= 0x80) {
            writeByte((current and 0x7F) or 0x80)
            current = current ushr 7
        }
        writeByte(current)
    }

    fun writeVarLong(value: Long) {
        require(value >= 0) { "Link varlong must be non-negative" }
        var current = value
        while (current >= 0x80) {
            writeByte(((current and 0x7F) or 0x80).toInt())
            current = current ushr 7
        }
        writeByte(current.toInt())
    }

    fun writeLengthPrefixed(value: ByteArray) {
        writeVarInt(value.size)
        writeBytes(value)
    }

    fun writeString(value: String) = writeLengthPrefixed(value.encodeToByteArray())

    fun toByteArray(): ByteArray = buffer.copyOf(length)
}

/** Strict reader. Every read is bounds-checked before it can allocate. */
internal class MirorLinkReader(
    private val bytes: ByteArray,
    private var position: Int = 0,
    private val limit: Int = bytes.size,
) {
    init {
        linkRequire(position in 0..limit && limit <= bytes.size) { "Link reader range is invalid" }
    }

    val remaining: Int get() = limit - position
    val exhausted: Boolean get() = remaining == 0

    private fun require(count: Int) {
        linkRequire(count >= 0) { "Link read length is negative" }
        linkRequire(remaining >= count) { "Link frame ended unexpectedly" }
    }

    fun readByte(): Int {
        require(1)
        return bytes[position++].toInt() and 0xFF
    }

    fun readBool(): Boolean {
        val raw = readByte()
        linkRequire(raw == 0 || raw == 1) { "Link boolean must be 0 or 1" }
        return raw == 1
    }

    fun readBytes(count: Int): ByteArray {
        require(count)
        val result = bytes.copyOfRange(position, position + count)
        position += count
        return result
    }

    fun skip(count: Int) {
        require(count)
        position += count
    }

    fun readIntLe(): Int {
        require(4)
        val b0 = bytes[position++].toInt() and 0xFF
        val b1 = bytes[position++].toInt() and 0xFF
        val b2 = bytes[position++].toInt() and 0xFF
        val b3 = bytes[position++].toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    fun readVarInt(): Int {
        var result = 0
        var shift = 0
        while (true) {
            linkRequire(shift <= 28) { "Link varint is too long" }
            val byte = readByte()
            val payload = byte and 0x7F
            if (shift == 28) {
                linkRequire(payload <= 0x07) { "Link varint overflows a signed 32-bit value" }
            }
            result = result or (payload shl shift)
            if ((byte and 0x80) == 0) {
                linkRequire(shift == 0 || payload != 0) { "Link varint is not canonical" }
                linkRequire(result >= 0) { "Link varint is negative" }
                return result
            }
            shift += 7
        }
    }

    fun readVarLong(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            // shift 56 contributes bits 56..62, which is the last group that fits in a
            // non-negative Long. Anything beyond it would set the sign bit.
            linkRequire(shift <= 56) { "Link varlong is too long" }
            val byte = readByte()
            val payload = (byte and 0x7F).toLong()
            result = result or (payload shl shift)
            if ((byte and 0x80) == 0) {
                linkRequire(shift == 0 || payload != 0L) { "Link varlong is not canonical" }
                linkRequire(result >= 0) { "Link varlong is negative" }
                return result
            }
            shift += 7
        }
    }

    /**
     * Reads a length-prefixed blob, rejecting the declared length against what is
     * actually present *before* allocating. This is the specific pattern the plan
     * requires: a peer cannot make Miror reserve memory by claiming a large body it
     * never sends.
     */
    fun readLengthPrefixed(maxBytes: Int): ByteArray {
        val declared = readVarInt()
        linkRequire(declared <= maxBytes) { "Link field is larger than its permitted bound" }
        linkRequire(declared <= remaining) { "Link field claims more bytes than the frame holds" }
        return readBytes(declared)
    }

    fun readString(maxBytes: Int): String {
        val raw = readLengthPrefixed(maxBytes)
        return raw.decodeToString(throwOnInvalidSequence = false)
    }
}

// A transport stall used to be raised here as MirorLinkTransportStalled. It is gone on
// purpose: a send that misses its deadline is now reported by
// `MirorLinkCoordinator.sendLocked` returning false, not thrown. Most sends run in
// coroutines launched directly on the process scope, where an escaping exception reaches
// Android's uncaught-exception handler and kills the app, so the failure has to be a value
// the caller must handle rather than one it can forget to catch.
