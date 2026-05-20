package com.helltar.vusan.tools.common

private const val ELLIPSIS = "..."
private const val MAX_FILENAME_CHARS = 120

fun String.sanitizeFilename(): String =
    trim()
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("""[<>:"|?*\p{Cntrl}]"""), "_")
        .trim('_', '.', ' ')
        .take(MAX_FILENAME_CHARS)

fun String.limitTo(maxChars: Int): String =
    when {
        maxChars <= 0 -> ""
        length <= maxChars -> this
        maxChars <= ELLIPSIS.length -> take(maxChars)
        else -> take(maxChars - ELLIPSIS.length).trimEnd() + ELLIPSIS
    }
