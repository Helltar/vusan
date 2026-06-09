package com.helltar.vusan.telegram

import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.content.MediaGroupContent
import dev.inmo.tgbotapi.types.message.content.MessageContent
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.message.content.TextedContent
import dev.inmo.tgbotapi.types.message.textsources.BotCommandTextSource
import dev.inmo.tgbotapi.types.message.textsources.MentionTextSource
import dev.inmo.tgbotapi.types.message.textsources.TextMentionTextSource

internal fun shouldHandle(message: CommonMessage<*>, botUserId: Long, botUsername: String?): Boolean {
    if (message.isPrivateChat) return true

    val isReplyToBot = message.replyAuthorIdOrNull() == botUserId
    val content = message.content.captionedContentOrNull() ?: return isReplyToBot

    return isReplyToBot ||
        hasBotMention(content, botUsername) ||
        hasBotTextMention(content, botUserId) ||
        hasTargetedBotCommand(content, botUsername)
}

// an album (media group) carries its caption on whichever part the sender attached it to — not
// necessarily the first, which is all `MediaGroupContent.text` exposes — so scan the parts.
internal fun MessageContent.captionedContentOrNull(): TextedContent? =
    when (this) {
        is MediaGroupContent<*> -> group.map { it.content }.firstOrNull { !it.text.isNullOrBlank() }
        is TextedContent -> this
        else -> null
    }

internal fun isBotCommand(content: TextContent): Boolean =
    content.textSources.firstOrNull() is BotCommandTextSource

internal fun normalizeUsername(value: String?): String? =
    value
        ?.trim()
        ?.removePrefix("@")
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }

private fun hasBotMention(content: TextedContent, botUsername: String?): Boolean {
    val expectedUsername = normalizeUsername(botUsername) ?: return false

    return content.textSources.any { source ->
        source is MentionTextSource && normalizeUsername(source.username.full) == expectedUsername
    }
}

private fun hasBotTextMention(content: TextedContent, botUserId: Long): Boolean =
    content.textSources.any { source ->
        source is TextMentionTextSource && source.user.id.chatId.long == botUserId
    }

private fun hasTargetedBotCommand(content: TextedContent, botUsername: String?): Boolean {
    val expectedUsername = normalizeUsername(botUsername) ?: return false

    return content.textSources.any { source ->
        source is BotCommandTextSource && normalizeUsername(source.username?.full) == expectedUsername
    }
}
