package com.helltar.vusan.common

import java.util.Locale

private const val ELLIPSIS = "..."
private const val MAX_FILENAME_CHARS = 120

/**
 * Collapses whitespace runs to single spaces, trims, and caps at [maxLength] with an ellipsis; `null` when
 * nothing is left. For metadata and snippets where layout whitespace is noise.
 */
internal fun String.collapseWhitespaceAndCap(maxLength: Int): String? {
    val normalized = trim().replace(Regex("\\s+"), " ")

    if (normalized.isBlank())
        return null

    return normalized.limitTo(maxLength)
}

/** Truncates to [maxChars] with an ellipsis, keeping inner whitespace. */
fun String.limitTo(maxChars: Int): String =

    when {
        maxChars <= 0 -> ""
        length <= maxChars -> this
        maxChars <= ELLIPSIS.length -> takeWholeChars(maxChars)
        else -> takeWholeChars(maxChars - ELLIPSIS.length).trimEnd() + ELLIPSIS
    }

/**
 * True when only whitespace and invisible format/control characters (zero-width spaces, joiners, BOM) remain.
 * [isBlank] counts those as content, so such a model reply would reach telegram and be rejected with `text
 * must be non-empty`, leaving the bot silent.
 */
internal fun String.isEffectivelyBlank(): Boolean =
    all { it.isWhitespace() || it.category == CharCategory.FORMAT || it.category == CharCategory.CONTROL }

/**
 * Wraps [content] in a `<tag>…</tag>` block, trimmed. A closing tag of the same name inside is escaped, so
 * command output, a transcript or a page cannot end the block early. Other blocks are left alone so nesting
 * works, including names that merely start with [tag] (`<user_message>` inside `<user>`); inbound text is
 * defused separately by `neutralizePromptBlocks`.
 */
internal fun xmlBlock(tag: String, content: String): String {
    val closing = Regex("</\\s*${Regex.escape(tag)}(?![\\w-])", RegexOption.IGNORE_CASE)

    return "<$tag>\n${closing.replace(content.trim()) { "&lt;${it.value.removePrefix("<")}" }}\n</$tag>"
}

fun String.sanitizeFilename(): String =
    trim()
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("""[<>:"|?*\p{Cntrl}]"""), "_")
        .trim('_', '.', ' ')
        .takeWholeChars(MAX_FILENAME_CHARS)

/**
 * Like [take], but never splits a UTF-16 surrogate pair: half an emoji is malformed UTF-16 and throws
 * [java.nio.charset.MalformedInputException] when encoded to UTF-8, e.g. into a request body.
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

/** A readable size: bytes under a kilobyte, whole kilobytes, then megabytes to one decimal. */
fun Long.asFileSize(): String =
    when {
        this >= BYTES_PER_MB -> String.format(Locale.ROOT, "%.1f MB", this / BYTES_PER_MB)
        this >= BYTES_PER_KB -> String.format(Locale.ROOT, "%.0f KB", this / BYTES_PER_KB)
        else -> "$this B"
    }

private const val BYTES_PER_KB = 1024.0
private const val BYTES_PER_MB = BYTES_PER_KB * 1024

/** Escapes the four characters telegram's `HTML` parse mode reads as markup. */
fun String.escapeHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
