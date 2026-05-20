package com.helltar.vusan.telegram

import dev.inmo.tgbotapi.bot.exceptions.RequestException

private val markdownErrorPatterns =
    listOf(
        "can't parse entities",
        "can't find end of",
        "wrong markdown",
        "entity beginning",
        "entity end"
    )

private val forbiddenPatterns =
    listOf(
        "forbidden",
        "bot can't initiate conversation",
        "bot was blocked",
        "user is deactivated",
        "chat not found"
    )

internal fun RequestException.isMarkdownError(): Boolean {
    val description = response.description?.lowercase() ?: return false
    return markdownErrorPatterns.any { it in description } || ("character" in description && "is reserved" in description)
}

internal fun RequestException.isForbidden(): Boolean {
    val description = response.description?.lowercase() ?: return false
    return forbiddenPatterns.any { it in description }
}
