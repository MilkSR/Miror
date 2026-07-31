package com.selenite.scanner.link

/**
 * Streaming SHA-256 for Miror Link transfer integrity.
 *
 * Deliberately implemented in common code rather than as an `expect`/`actual` over
 * `MessageDigest` and CommonCrypto. Link compares a digest computed on one device
 * against a digest computed on another, so the two implementations agreeing is the
 * whole point; a single shared implementation cannot drift, and it is exercised by
 * the same NIST vectors on every platform.
 *
 * This detects truncation and corruption. It is not a signature, and per the
 * settled product decisions Link adds no provenance or trust layer -- a
 * deliberately modified peer can produce a digest that matches its own modified
 * bytes.
 */
internal class MirorSha256 {
    private val state = intArrayOf(
        0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6,
        0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19,
    )
    private val block = ByteArray(BLOCK_BYTES)
    private val schedule = IntArray(64)
    private var blockSize = 0
    private var totalBytes = 0L
    private var finished = false

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        check(!finished) { "SHA-256 already finalized" }
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "SHA-256 update range is out of bounds"
        }
        totalBytes += length
        var index = offset
        var remaining = length

        if (blockSize > 0) {
            val fill = minOf(BLOCK_BYTES - blockSize, remaining)
            bytes.copyInto(block, blockSize, index, index + fill)
            blockSize += fill
            index += fill
            remaining -= fill
            if (blockSize == BLOCK_BYTES) {
                compress(block, 0)
                blockSize = 0
            }
        }

        while (remaining >= BLOCK_BYTES) {
            compress(bytes, index)
            index += BLOCK_BYTES
            remaining -= BLOCK_BYTES
        }

        if (remaining > 0) {
            bytes.copyInto(block, 0, index, index + remaining)
            blockSize = remaining
        }
    }

    fun digest(): ByteArray {
        check(!finished) { "SHA-256 already finalized" }
        finished = true

        val bitLength = totalBytes * 8
        block[blockSize++] = 0x80.toByte()
        if (blockSize > BLOCK_BYTES - 8) {
            while (blockSize < BLOCK_BYTES) block[blockSize++] = 0
            compress(block, 0)
            blockSize = 0
        }
        while (blockSize < BLOCK_BYTES - 8) block[blockSize++] = 0
        for (shift in 7 downTo 0) {
            block[blockSize++] = ((bitLength ushr (shift * 8)) and 0xFF).toByte()
        }
        compress(block, 0)

        val out = ByteArray(32)
        for (i in 0 until 8) {
            val value = state[i]
            out[i * 4] = (value ushr 24).toByte()
            out[i * 4 + 1] = (value ushr 16).toByte()
            out[i * 4 + 2] = (value ushr 8).toByte()
            out[i * 4 + 3] = value.toByte()
        }
        return out
    }

    private fun compress(source: ByteArray, offset: Int) {
        val w = schedule
        for (i in 0 until 16) {
            val base = offset + i * 4
            w[i] = ((source[base].toInt() and 0xFF) shl 24) or
                ((source[base + 1].toInt() and 0xFF) shl 16) or
                ((source[base + 2].toInt() and 0xFF) shl 8) or
                (source[base + 3].toInt() and 0xFF)
        }
        for (i in 16 until 64) {
            val x = w[i - 15]
            val y = w[i - 2]
            val s0 = x.rotateRight(7) xor x.rotateRight(18) xor (x ushr 3)
            val s1 = y.rotateRight(17) xor y.rotateRight(19) xor (y ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }

        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]

        for (i in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = h + s1 + ch + ROUND_CONSTANTS[i] + w[i]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + maj

            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }

        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
        state[4] += e
        state[5] += f
        state[6] += g
        state[7] += h
    }

    companion object {
        private const val BLOCK_BYTES = 64
        const val DIGEST_BYTES = 32

        fun hash(bytes: ByteArray): ByteArray = MirorSha256().apply { update(bytes) }.digest()

        private val ROUND_CONSTANTS: IntArray = longArrayOf(
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
            0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
            0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
            0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
            0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
            0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
            0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
            0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
            0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
        ).let { source -> IntArray(64) { source[it].toInt() } }
    }
}

internal fun ByteArray.toHexLowercase(): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    forEach { byte ->
        val value = byte.toInt() and 0xFF
        out.append(digits[value ushr 4])
        out.append(digits[value and 0x0F])
    }
    return out.toString()
}
