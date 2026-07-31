package com.selenite.scanner.link

/**
 * Reads and writes the card photos exchanged over Link.
 *
 * Photos are fetched **on demand, one card at a time**, when the viewer opens a card.
 * Sending a whole collection's originals up front would be hundreds of megabytes for
 * something the recipient may never look at; this way a session that browses three cards
 * transfers three downscaled images.
 *
 * Received photos live in a session-scoped cache and are dropped when the Link ends,
 * matching the rule that a shared collection is temporary and read-only.
 */
internal interface MirorLinkPhotoCodec {
    /**
     * Sender side: load the user's own photo and re-encode it under [maxBytes]. Returns
     * null when there is no photo or it cannot be read.
     */
    suspend fun readDownscaled(path: String, maxBytes: Int): ByteArray?

    /** Receiver side: cache a received photo and return a path the viewer can render. */
    suspend fun writeSessionPhoto(cardId: String, bytes: ByteArray): String?

    /** Drops every photo received this session. */
    fun clearSessionPhotos()
}

/**
 * The gate between a peer's bytes and the platform image decoder.
 *
 * A received photo is verified against its digest, which proves the transfer was intact
 * -- never that the bytes are an image. Everything downstream (the cache file, the viewer)
 * hands them to the platform decoder, which on Android is Skia: a large C++ parser, and
 * historically one of the most attacked surfaces in mobile software.
 *
 * That bug class is not ours to fix. What is ours is refusing to feed it arbitrary input.
 *
 * The check is exact rather than heuristic: `ImageStorageManager` writes JPEG on both
 * platforms, and the send path re-encodes to JPEG regardless of the source format, so a
 * legitimate peer always sends JPEG. Anything else is definitionally not something Miror
 * produced, and rejecting it costs no real photo.
 *
 * Worth understanding what this buys, because it is more than it looks: decoders are
 * selected by *content sniffing*, not by file extension, so without this a peer could
 * reach the PNG, WebP, GIF, BMP or HEIF decoders simply by sending their bytes. Requiring
 * the JPEG marker narrows six independent native parsers down to one.
 */
internal object MirorLinkImageGate {

    /** JPEG SOI marker followed by the start of any valid marker segment. */
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    /**
     * Generous ceiling on a single edge.
     *
     * The sender downscales to a 900-pixel longest edge, so anything approaching this is
     * already not something Miror produced. 2048 leaves room for a future sender that
     * ships larger photos without this becoming the thing that breaks; 8192 left room for
     * a 268-megapixel allocation, which is room nobody needed.
     */
    const val MAX_DIMENSION = 2_048

    /**
     * Total pixels, in Long arithmetic.
     *
     * Deliberately below MAX_DIMENSION squared, or the area check could never bite: two
     * legal edges would always multiply to a legal area and the second bound would be
     * decoration. The sender's own output tops out near 900x1260, about 1.1 megapixels,
     * so two megapixels is comfortably generous and still meaningful.
     *
     * Multiplied as Long because `Int` overflow is precisely how a dimension check stops
     * checking anything.
     */
    const val MAX_PIXELS = 2L * 1024L * 1024L

    fun looksLikeJpeg(bytes: ByteArray): Boolean {
        if (bytes.size < JPEG_MAGIC.size) return false
        return JPEG_MAGIC.indices.all { bytes[it] == JPEG_MAGIC[it] }
    }

    fun hasPlausibleDimensions(width: Int, height: Int): Boolean {
        if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) return false
        return width.toLong() * height.toLong() <= MAX_PIXELS
    }
}

/** Null where photo relay is unsupported; Link then simply never offers or requests one. */
internal expect fun createMirorLinkPhotoCodec(): MirorLinkPhotoCodec?
