package com.helltar.vusan.tools.youtube

private val CUE_TAG = Regex("<[^>]*>")
private val CUE_ID = Regex("""^\d+$""")
private val WHITESPACE = Regex("""\s+""")

private val HTML_ENTITIES =
    mapOf(
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&nbsp;" to " "
    )

/**
 * Flattens a WebVTT subtitle track into one line of plain text, dropping the header, cue ids,
 * timings, and inline karaoke tags.
 *
 * YouTube's auto-generated captions scroll: every cue repeats the tail of the previous cue, so
 * a naive concatenation says everything two or three times. Consecutive duplicate lines are
 * collapsed to keep the transcript readable and to roughly halve its token cost.
 */
internal fun parseWebVtt(vtt: String): String {
    val lines = mutableListOf<String>()

    vtt.lineSequence().forEach { raw ->
        val line = raw.trim()

        if (line.isCueMetadata()) return@forEach

        val text = line.stripCueMarkup()

        if (text.isNotEmpty() && text != lines.lastOrNull()) {
            lines += text
        }
    }

    return lines.joinToString(" ")
}

private fun String.isCueMetadata(): Boolean =
    isEmpty() ||
            contains("-->") ||
            startsWith("WEBVTT") ||
            startsWith("NOTE") ||
            startsWith("STYLE") ||
            startsWith("Kind:") ||
            startsWith("Language:") ||
            CUE_ID.matches(this)

private fun String.stripCueMarkup(): String =
    replace(CUE_TAG, "")
        .let { text -> HTML_ENTITIES.entries.fold(text) { acc, (entity, char) -> acc.replace(entity, char) } }
        .replace(WHITESPACE, " ")
        .trim()
