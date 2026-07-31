package com.selenite.scanner.share

/**
 * Deterministic ordering helpers shared by the codec and the repository.
 *
 * These outlived `CatalogOrdinalResolver`, which addressed cards by their position in
 * this ordering and was retired with the v1 wire format. The ordering itself is still
 * useful wherever the frozen wire's unsigned UTF-8 byte order is needed -- notably
 * variant tags and the unresolved-id list. Kotlin's ordinary string order uses UTF-16
 * code units, which differs for some supplementary Unicode characters.
 */
object ShareSorting {
    fun sortCardIdsByUtf8Ordinal(cardIds: List<String>): List<String> =
        cardIds.sortedWith(::compareUtf8Ordinal)

    fun compareUtf8Ordinal(left: String, right: String): Int {
        val leftBytes = left.encodeToByteArray()
        val rightBytes = right.encodeToByteArray()
        val minSize = minOf(leftBytes.size, rightBytes.size)
        for (index in 0 until minSize) {
            val diff = (leftBytes[index].toInt() and 0xFF) - (rightBytes[index].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return leftBytes.size - rightBytes.size
    }
}
