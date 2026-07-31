package com.selenite.scanner.share

/**
 * Production dispatcher for self-contained collection manifests.
 *
 * New manifests always use [AdaptiveIdentityCodec]. The unpublished `MRSH` versions are
 * recognized only by their fixed header so callers can show a clear retired-format
 * message. Their bodies are never checksummed, copied, parsed, or allocated from.
 */
object ShareManifestBinaryCodec {
    /**
     * The one public statement of how large an encoded manifest may be.
     *
     * Transports that carry raw manifests -- currently Miror Link -- must bound their
     * reassembly buffer with this rather than restating the number, so the transport
     * and the decoder can never disagree about what is acceptable.
     */
    const val MAX_MANIFEST_BYTES: Int = AdaptiveIdentityCodec.MAX_ENVELOPE_BYTES

    fun encode(manifest: MirorShareManifest): ByteArray =
        AdaptiveIdentityCodec.encode(manifest)

    fun decode(bytes: ByteArray): MirorShareDecodeResult {
        if (AdaptiveIdentityCodec.looksLike(bytes)) {
            return AdaptiveIdentityCodec.decode(bytes)
        }

        retiredMrshVersion(bytes)?.let { version ->
            return MirorShareDecodeResult.RetiredFormat(version)
        }

        return MirorShareDecodeResult.InvalidData("Unknown share payload magic")
    }

    private fun retiredMrshVersion(bytes: ByteArray): Int? {
        if (bytes.size < RETIRED_HEADER_SIZE ||
            bytes[0] != RETIRED_MAGIC_M ||
            bytes[1] != RETIRED_MAGIC_R ||
            bytes[2] != RETIRED_MAGIC_S ||
            bytes[3] != RETIRED_MAGIC_H
        ) {
            return null
        }
        val version = bytes[4].toInt() and 0xFF
        return version.takeIf { it in RETIRED_VERSIONS }
    }

    private const val RETIRED_HEADER_SIZE = 5
    private const val RETIRED_VERSION_ORDINAL = 1
    private const val RETIRED_VERSION_IDENTITY_DRAFT = 2
    private val RETIRED_VERSIONS =
        RETIRED_VERSION_ORDINAL..RETIRED_VERSION_IDENTITY_DRAFT

    private val RETIRED_MAGIC_M = 'M'.code.toByte()
    private val RETIRED_MAGIC_R = 'R'.code.toByte()
    private val RETIRED_MAGIC_S = 'S'.code.toByte()
    private val RETIRED_MAGIC_H = 'H'.code.toByte()
}
