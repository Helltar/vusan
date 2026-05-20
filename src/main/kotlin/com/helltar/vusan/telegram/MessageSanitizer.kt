package com.helltar.vusan.telegram

import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.message.textsources.MentionTextSource
import dev.inmo.tgbotapi.types.message.textsources.TextMentionTextSource

internal fun sanitizeUserText(content: TextContent, botUserId: Long, botUsername: String?): String {
    val expectedUsername = normalizeUsername(botUsername)
    val sources = content.textSources

    if (sources.isEmpty()) return content.text.trim()

    val sanitized =
        sources.joinToString(separator = "") { source ->
            when (source) {
                is MentionTextSource if expectedUsername != null && normalizeUsername(source.username.full) == expectedUsername -> ""
                is TextMentionTextSource if source.user.id.chatId.long == botUserId -> ""
                else -> source.source
            }
        }

    return sanitized.cleanupAfterMentionRemoval()
}

private fun String.cleanupAfterMentionRemoval(): String =
    replace(Regex("[\\t ]+([,.;:!?])"), "$1")
        .replace(Regex("(^|\\n)[\\t ]*[,.;:!?-]+[\\t ]*"), "$1")
        .replace(Regex("[\\t ]{2,}"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .trim()
