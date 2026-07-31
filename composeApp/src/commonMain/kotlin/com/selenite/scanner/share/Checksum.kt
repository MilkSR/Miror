package com.selenite.scanner.share

object ShareChecksum {
    fun crc32(bytes: ByteArray): Int {
        var crc = -1
        for (byte in bytes) {
            var value = (crc xor (byte.toInt() and 0xFF))
            repeat(8) {
                val mask = -(value and 1)
                value = (value ushr 1) xor (0xEDB88320.toInt() and mask)
            }
            crc = value
        }
        return crc.inv()
    }

    fun fnv1a64(bytes: ByteArray, seed: Long = FNV_OFFSET_BASIS): Long {
        var hash = seed
        for (byte in bytes) {
            hash = hash xor (byte.toLong() and 0xFFL)
            hash *= FNV_PRIME
        }
        return hash
    }

    private const val FNV_OFFSET_BASIS: Long = -3750763034362895579L
    private const val FNV_PRIME: Long = 1099511628211L
}
