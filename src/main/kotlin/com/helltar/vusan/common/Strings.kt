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

    if (normalized.isBlank())
        return null

    return if (normalized.length <= maxLength)
        normalized
    else
        normalized.takeWholeChars(maxLength - 1).trimEnd() + ELLIPSIS
}

/** Truncates to [maxChars] (appending an ellipsis when truncated) while preserving inner whitespace. */
fun String.limitTo(maxChars: Int): String =
    when {
        maxChars <= 0 -> ""
        length <= maxChars -> this
        maxChars <= ELLIPSIS.length -> takeWholeChars(maxChars)
        else -> takeWholeChars(maxChars - ELLIPSIS.length).trimEnd() + ELLIPSIS
    }

/**
 * True when the string carries no renderable content: only whitespace plus invisible format/control
 * characters (zero-width spaces, joiners, BOM, etc.). Kotlin's [isBlank] treats those zero-width chars
 * as non-blank, so a model reply consisting solely of them slips through and Telegram rejects it with
 * `text must be non-empty` — leaving the bot silent. Use this to detect such replies up front.
 */
internal fun String.isEffectivelyBlank(): Boolean =
    all { it.isWhitespace() || it.category == CharCategory.FORMAT || it.category == CharCategory.CONTROL }

/** Wraps [content] in an XML-style `<tag>…</tag>` block, trimming surrounding whitespace. */
internal fun xmlBlock(tag: String, content: String): String =
    "<$tag>\n${content.trim()}\n</$tag>"

fun String.sanitizeFilename(): String =
    trim()
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("""[<>:"|?*\p{Cntrl}]"""), "_")
        .trim('_', '.', ' ')
        .takeWholeChars(MAX_FILENAME_CHARS)

/**
 * Like [take], but never cuts through the middle of a UTF-16 surrogate pair. Slicing an emoji
 * (or any astral-plane character) in half leaves a dangling surrogate; the resulting string is
 * malformed UTF-16 and throws [java.nio.charset.MalformedInputException] when later encoded to
 * UTF-8 — e.g. when the prompt is serialized into an HTTP request body. Dropping the trailing
 * high surrogate keeps every truncation result valid.
 */
private fun String.takeWholeChars(n: Int): String {
    val end = n.coerceIn(0, length)

    val safeEnd =
        if (end in 1 until length && this[end - 1].isHighSurrogate())
            end - 1
        else
            end

    return substring(0, safeEnd)
}
