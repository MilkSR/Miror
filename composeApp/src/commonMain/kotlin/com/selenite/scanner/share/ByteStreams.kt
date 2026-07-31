package com.selenite.scanner.share

internal class ShareByteWriter {
    private val bytes = mutableListOf<Byte>()

    fun writeByte(value: Int) {
        bytes.add((value and 0xFF).toByte())
    }

    fun writeBytes(value: ByteArray) {
        value.forEach { bytes.add(it) }
    }

    fun writeIntLittleEndian(value: Int) {
        writeByte(value)
        writeByte(value ushr 8)
        writeByte(value ushr 16)
        writeByte(value ushr 24)
    }

    fun writeLongLittleEndian(value: Long) {
        repeat(8) { index ->
            writeByte((value ushr (index * 8)).toInt())
        }
    }

    fun writeVarInt(value: Int) {
        require(value >= 0) { "VarInt value must be non-negative" }
        var current = value
        while (current >= 0x80) {
            writeByte((current and 0x7F) or 0x80)
            current = current ushr 7
        }
        writeByte(current)
    }

    fun writeString(value: String) {
        val encoded = value.encodeToByteArray()
        writeVarInt(encoded.size)
        writeBytes(encoded)
    }

    fun toByteArray(): ByteArray = bytes.toByteArray()
}

internal class ShareByteCursor(private val bytes: ByteArray) {
    private var position: Int = 0

    val remaining: Int
        get() = bytes.size - position

    fun readByte(): Int {
        requireRemaining(1)
        return bytes[position++].toInt() and 0xFF
    }

    fun readBytes(size: Int): ByteArray {
        require(size >= 0) { "size must be non-negative" }
        requireRemaining(size)
        val result = bytes.copyOfRange(position, position + size)
        position += size
        return result
    }

    fun readIntLittleEndian(): Int {
        val b0 = readByte()
        val b1 = readByte()
        val b2 = readByte()
        val b3 = readByte()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    fun readLongLittleEndian(): Long {
        var result = 0L
        repeat(8) { index ->
            result = result or (readByte().toLong() shl (index * 8))
        }
        return result
    }

    fun readVarInt(): Int {
        var result = 0
        var shift = 0
        while (shift <= 28) {
            val byte = readByte()
            result = result or ((byte and 0x7F) shl shift)
            if ((byte and 0x80) == 0) return result
            shift += 7
        }
        throw IllegalArgumentException("VarInt is too long")
    }

    fun readString(): String {
        val size = readVarInt()
        return readBytes(size).decodeToString()
    }

    private fun requireRemaining(size: Int) {
        if (remaining < size) {
            throw IllegalArgumentException("Unexpected end of share payload")
        }
    }
}
