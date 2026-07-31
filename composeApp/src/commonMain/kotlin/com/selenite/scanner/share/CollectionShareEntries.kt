package com.selenite.scanner.share

import com.selenite.scanner.model.Card
import com.selenite.scanner.model.CollectionItem
import com.selenite.scanner.util.isVariantTag
import com.selenite.scanner.util.parseCollectionTags
import kotlin.math.roundToInt

/**
 * Converts owned physical database rows into the complete share-visible representation.
 *
 * The field set deliberately matches the previous collection-share format: exact card
 * identity, printing, first-edition state, every internal physical-variant tag (including
 * language and detailed product/foil tags), grading, grade score, condition, and quantity.
 * Local photos, notes, dates, cached prices, tracking tags, and user tags remain private.
 *
 * [entryIds] names exact physical rows. The Collection UI expands each selected visual
 * stack to all of its physical IDs before calling this function, so normal multi-select
 * keeps whole-stack behavior without allowing an incomplete or stale caller to leak an
 * unselected row from the same stack.
 */
/**
 * Splits a decoded manifest into what this device can name and what it cannot.
 *
 * Decoding is catalog-independent, so this is the only place a receiver's card data
 * matters. An entry either matches an exact id or it does not: it can never resolve to a
 * *different* card, which is what makes importing the remainder safe. The unresolved
 * total counts physical cards rather than entries, because a single unknown entry may
 * carry a quantity, and telling someone "1 missing" when five cards are absent would
 * understate it.
 *
 * Kept free of Room so the partition is directly testable; the repository supplies the
 * lookup it has already performed.
 */
internal fun buildSharedCollection(
    manifest: MirorShareManifest,
    cardsById: Map<String, Card>,
): SharedCollection {
    val resolved = manifest.entries.map { entry ->
        SharedCollectionItem(entry = entry, card = cardsById[entry.cardId])
    }
    val known = resolved.filter { it.card != null }
    val missing = resolved.filter { it.card == null }

    return SharedCollection(
        metadata = manifest.metadata,
        items = known,
        unresolvedCardIds = ShareSorting.sortCardIdsByUtf8Ordinal(
            missing.map { it.entry.cardId }.distinct()
        ),
        droppedCount = missing.sumOf { it.entry.quantity.toLong() },
        // New cards are routinely added to an existing set, so set membership cannot
        // distinguish "newer" from "missing". Any unknown immutable id is worth offering
        // the existing card-data refresh for.
        canRefreshCardData = missing.isNotEmpty(),
    )
}

internal fun buildCollectionShareEntries(
    ownedStacks: List<CollectionItem>,
    entryIds: Set<Long>? = null,
): List<MirorShareEntry> {
    return ownedStacks
        .asSequence()
        .flatMap { it.stackEntries.asSequence() }
        .filter { it.dateAdded > 0L }
        .filter { entryIds == null || it.id in entryIds }
        .map { entry ->
            val variantTags = ShareSorting.sortCardIdsByUtf8Ordinal(
                parseCollectionTags(entry.tags).filter(::isVariantTag)
            )
            MirorShareEntry(
                cardId = entry.cardId,
                quantity = 1,
                variant = MirorPhysicalVariant(
                    printingType = entry.printingType,
                    isFirstEdition = entry.isFirstEdition,
                    variantTags = variantTags,
                ),
                condition = MirorCondition(
                    isGraded = entry.isGraded,
                    gradeScoreTenths = entry.gradeScore
                        ?.takeIf { entry.isGraded }
                        ?.let { (it * 10f).roundToInt().coerceIn(0, 100) },
                    rawCondition = entry.rawCondition,
                ),
            )
        }
        .toList()
}
