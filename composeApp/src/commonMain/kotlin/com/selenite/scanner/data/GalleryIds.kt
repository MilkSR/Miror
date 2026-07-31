package com.selenite.scanner.data

/**
 * The single rule for reading `master_index_v3_ids.txt`.
 *
 * Content releases carry a trailing signature line (`#miror-sig-v1 <keyId> <base64>`),
 * which is metadata about the release rather than a gallery row. Every reader of the ID
 * list must skip it, because the row count is load-bearing in three separate places:
 *
 *  - `MLManager` sizes the fp16 gallery as `ids.size * EMBEDDING_DIM` and refuses to load
 *    on a mismatch, so one phantom ID disables scanning outright.
 *  - `ContentUpdateManager.validatePendingGallery` derives the expected `.bin` byte count
 *    the same way, so one phantom ID rejects an otherwise valid release.
 *  - `printlens_v3_families.tsv` addresses the global gallery by **row position**
 *    (`globalRow`). Any shift in those positions makes PrintLens rerank against the wrong
 *    card -- silently, with no error anywhere.
 *
 * That last one is why the signature is appended **last** and never prepended: appending
 * leaves every existing row index untouched. This rule and that placement are two halves
 * of the same guarantee, so neither can be changed alone.
 *
 * Comments are filtered generally rather than by exact prefix match. No gallery ID begins
 * with `#`, and a forgiving rule means a future metadata line cannot resurrect this bug.
 */
object GalleryIds {

    /** Marker for the detached release signature. Versioned so the format can evolve. */
    const val SIGNATURE_PREFIX = "#miror-sig-"

    private const val COMMENT_MARKER = '#'

    fun isSignatureLine(line: String): Boolean = line.trim().startsWith(SIGNATURE_PREFIX)

    /** True for a line that denotes an actual gallery row. */
    fun isIdLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.isNotEmpty() && trimmed[0] != COMMENT_MARKER
    }

    /** The gallery IDs in file order, with blank lines and metadata removed. */
    fun parse(lines: Sequence<String>): List<String> =
        lines.filter(::isIdLine).map { it.trim() }.toList()

    fun parse(lines: Iterable<String>): List<String> = parse(lines.asSequence())

    /**
     * The trailing signature line, or null when the file carries none.
     *
     * Only the final line is considered. A signature anywhere else is not accepted --
     * see [com.selenite.scanner.data.ContentSignature] for why position is part of the
     * contract rather than a convention.
     */
    fun signatureLine(lines: List<String>): String? =
        lines.lastOrNull { it.isNotBlank() }?.takeIf(::isSignatureLine)
}
