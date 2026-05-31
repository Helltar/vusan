package com.helltar.vusan.common

private const val ELLIPSIS = "..."
private const val MAX_FILENAME_CHARS = 120

/**
 * Collapses all runs of whitespace to single spaces, trims, and caps the result at [maxLength]
 * (appending an ellipsis when truncated). Returns `null` when nothing is left after normalization.
 * Use for metadata and history snippets where layout whitespace is noise.
 */
internal fun String.collapseWhitespaceAndCap(maxLength: Int): String? {
    val normalized = trim().replace(Regex("\\s+"), " ")
    if (normalized.isBlank()) return null
    return if (normalized.length <= maxLength) normalized else normalized.take(maxLength - 1).trimEnd() + ELLIPSIS
}

/** Truncates to [maxChars] (appending an ellipsis when truncated) while preserving inner whitespace. */
fun String.limitTo(maxChars: Int): String =
    when {
        maxChars <= 0 -> ""
        length <= maxChars -> this
        maxChars <= ELLIPSIS.length -> take(maxChars)
        else -> take(maxChars - ELLIPSIS.length).trimEnd() + ELLIPSIS
    }

/** Wraps [content] in an XML-style `<tag>…</tag>` block, trimming surrounding whitespace. */
internal fun xmlBlock(tag: String, content: String): String = "<$tag>\n${content.trim()}\n</$tag>"

fun String.sanitizeFilename(): String =
    trim()
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("""[<>:"|?*\p{Cntrl}]"""), "_")
        .trim('_', '.', ' ')
        .take(MAX_FILENAME_CHARS)
