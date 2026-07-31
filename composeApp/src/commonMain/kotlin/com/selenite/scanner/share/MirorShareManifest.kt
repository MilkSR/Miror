package com.selenite.scanner.share

import com.selenite.scanner.model.Card

/**
 * The only supported format is the catalog-independent, frozen arithmetic grammar.
 * Every unpublished `MRSH` format was retired before this release.
 */
enum class MirorShareEncodingMode {
    IDENTITY_ARITH_V1
}

/**
 * There is deliberately no catalog fingerprint, epoch, or ordinal stride here.
 *
 * Anything derived from the *shape* of a growing catalog can move when content arrives.
 * An earlier design addressed cards by a catalog-derived ordinal, and a single new card
 * pushing one suffix prefix past a power of two shifted every later prefix -- silently
 * re-pointing outstanding codes at different real cards. A code now carries the card's
 * own identity, so nothing about the catalog can change what it means.
 */
data class MirorShareMetadata(
    val schemaVersion: Int,
    val catalogAssetVersion: Int
)

data class MirorShareManifest(
    val metadata: MirorShareMetadata,
    val encodingMode: MirorShareEncodingMode,
    val entries: List<MirorShareEntry>
)

data class MirorShareEntry(
    val cardId: String,
    val quantity: Int = 1,
    val variant: MirorPhysicalVariant = MirorPhysicalVariant(),
    val condition: MirorCondition = MirorCondition()
) {
    init {
        require(cardId.isNotBlank()) { "cardId must not be blank" }
        require(quantity > 0) { "quantity must be positive" }
    }
}

data class MirorPhysicalVariant(
    val printingType: String = PRINTING_STANDARD,
    val isFirstEdition: Boolean = false,
    val variantTags: List<String> = emptyList()
) {
    companion object {
        const val PRINTING_STANDARD = "Standard"
        const val PRINTING_HOLO = "Holo"
        const val PRINTING_REVERSE_HOLO = "Reverse Holo"
    }
}

data class MirorCondition(
    val isGraded: Boolean = false,
    val gradeScoreTenths: Int? = null,
    val rawCondition: String? = "NM"
) {
    init {
        require(gradeScoreTenths == null || gradeScoreTenths in 0..100) {
            "gradeScoreTenths must be in 0..100"
        }
    }
}

data class SharedCollection(
    val metadata: MirorShareMetadata,
    val items: List<SharedCollectionItem>,
    val unresolvedCardIds: List<String>,
    /** Total physical quantity whose exact IDs this device's card data cannot name. */
    val droppedCount: Long = 0,
    /** True when unknown immutable IDs may become available after a card-data refresh. */
    val canRefreshCardData: Boolean = false
)

data class SharedCollectionItem(
    val entry: MirorShareEntry,
    val card: Card?
)

sealed class MirorShareDecodeResult {
    /**
     * Decoding always yields the sender's full entry list: the payload is self-contained,
     * so it succeeds without any card data at all. Whether this device *knows* each card
     * is a separate question, answered when the manifest is resolved against the catalog.
     */
    data class Success(val manifest: MirorShareManifest) : MirorShareDecodeResult()

    /** An unpublished `MRSH` code from before the adaptive format was frozen. */
    data class RetiredFormat(val version: Int) : MirorShareDecodeResult()

    /** A valid Sigil family marker whose required version/type this app predates. */
    data class UnsupportedFormat(val description: String) : MirorShareDecodeResult()

    data class InvalidData(val message: String) : MirorShareDecodeResult()
}
