package com.helltar.vusan.telegram

import org.telegram.telegrambots.meta.api.objects.EntityType
import org.telegram.telegrambots.meta.api.objects.message.Message

// [captionSource] is the album part carrying the caption when [message] anchors a media group;
// for single messages it is the message itself.
internal fun shouldHandle(
    message: Message,
    botUserId: Long,
    botUsername: String?,
    captionSource: Message = message
): Boolean {
    if (message.isPrivateChat) return true

    val isReplyToBot = message.replyAuthorIdOrNull() == botUserId
    val content = captionSource.messageTextOrNull() ?: return isReplyToBot

    return isReplyToBot ||
        hasBotMention(content, botUsername) ||
        hasBotTextMention(content, botUserId) ||
        hasTargetedBotCommand(content, botUsername)
}

// an album (media group) carries its caption on whichever part the sender attached it to — not
// necessarily the first — so scan the parts.
internal fun List<Message>.captionedPartOrNull(): Message? =
    firstOrNull { !it.caption.isNullOrBlank() }

internal fun Message.messageTextOrNull(): MessageText? =
    text?.let { MessageText(it, entities.orEmpty()) }
        ?: caption?.let { MessageText(it, captionEntities.orEmpty()) }

internal fun isBotCommand(content: MessageText): Boolean =
    content.leadingBotCommandOrNull() != null

// leading bot command of a message, e.g. `/start@VusanBot arg` -> command "start" targeting "VusanBot".
internal data class BotCommand(val command: String, val targetUsername: String?)

internal fun MessageText.leadingBotCommandOrNull(): BotCommand? {
    val entity = entities.firstOrNull { it.type == EntityType.BOTCOMMAND && it.offset == 0 } ?: return null
    val span = entitySpan(entity).removePrefix("/")

    return BotCommand(
        command = span.substringBefore('@').lowercase(),
        targetUsername = span.substringAfter('@', "").ifEmpty { null }
    )
}

internal fun normalizeUsername(value: String?): String? =
    value
        ?.trim()
        ?.removePrefix("@")
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }

private fun hasBotMention(content: MessageText, botUsername: String?): Boolean {
    val expectedUsername = normalizeUsername(botUsername) ?: return false

    return content.entities.any { entity ->
        entity.type == EntityType.MENTION && normalizeUsername(content.entitySpan(entity)) == expectedUsername
    }
}

private fun hasBotTextMention(content: MessageText, botUserId: Long): Boolean =
    content.entities.any { entity ->
        entity.type == EntityType.TEXTMENTION && entity.user?.id == botUserId
    }

private fun hasTargetedBotCommand(content: MessageText, botUsername: String?): Boolean {
    val expectedUsername = normalizeUsername(botUsername) ?: return false

    return content.entities.any { entity ->
        entity.type == EntityType.BOTCOMMAND &&
            normalizeUsername(content.entitySpan(entity).substringAfter('@', "")) == expectedUsername
    }
}
