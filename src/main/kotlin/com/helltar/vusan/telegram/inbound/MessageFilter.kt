package com.helltar.vusan.telegram.inbound

import org.telegram.telegrambots.meta.api.objects.EntityType
import org.telegram.telegrambots.meta.api.objects.message.Message

// [captionSource] is the album part carrying the caption when [message] anchors a media group, otherwise the
// message itself.
internal fun shouldHandle(
    message: Message,
    botUserId: Long,
    botUsernames: Collection<String>,
    captionSource: Message = message,
): Boolean {
    // an ephemeral command reaches this bot alone, so it is addressed however spelled
    if (message.isPrivateChat || message.isEphemeral) return true

    val isReplyToBot = message.replyAuthorIdOrNull() == botUserId
    val content =
        captionSource.messageTextOrNull()
            ?: return isReplyToBot || captionSource.richMessageMentionsBot(botUserId, botUsernames)

    return isReplyToBot ||
        hasBotMention(content, botUsernames) ||
        hasBotTextMention(content, botUserId) ||
        hasTargetedBotCommand(content, botUsernames)
}

// an album carries its caption on whichever part the sender chose, not necessarily the first.
internal fun List<Message>.captionedPartOrNull(): Message? =
    firstOrNull { !it.caption.isNullOrBlank() }

internal fun Message.messageTextOrNull(): MessageText? =
    text?.let { MessageText(it, entities.orEmpty()) }
        ?: caption?.let { MessageText(it, captionEntities.orEmpty()) }

internal fun isBotCommand(content: MessageText): Boolean =
    content.leadingBotCommandOrNull() != null

// a message's leading bot command, e.g. `/start@VusanBot arg` -> command "start" targeting "VusanBot".
internal data class BotCommand(val command: String, val targetUsername: String?)

internal fun MessageText.leadingBotCommandOrNull(): BotCommand? {
    val entity = entities.firstOrNull { it.type == EntityType.BOTCOMMAND && it.offset == 0 } ?: return null
    val span = entitySpan(entity).removePrefix("/")

    return BotCommand(
        command = span.substringBefore('@').lowercase(),
        targetUsername = span.substringAfter('@', "").ifEmpty { null },
    )
}

internal fun normalizeUsername(value: String?): String? =
    value
        ?.trim()
        ?.removePrefix("@")
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }

internal fun Collection<String>.normalizedUsernames(): Set<String> =
    mapNotNull(::normalizeUsername).toSet()

// a rich message has no entities, so the mention is matched on the flattened markdown: `mention` renders as
// `@name`, `text_mention` as a `tg://user?id=` link.
private fun Message.richMessageMentionsBot(botUserId: Long, botUsernames: Collection<String>): Boolean {
    val markdown = richMessage?.toRichMarkdown() ?: return false

    return markdown.contains("tg://user?id=$botUserId") ||
        botUsernames.normalizedUsernames().any { Regex("@$it\\b", RegexOption.IGNORE_CASE).containsMatchIn(markdown) }
}

private fun hasBotMention(content: MessageText, botUsernames: Collection<String>): Boolean {
    val expected = botUsernames.normalizedUsernames()

    return content.entities.any { entity ->
        entity.type == EntityType.MENTION && normalizeUsername(content.entitySpan(entity)) in expected
    }
}

private fun hasBotTextMention(content: MessageText, botUserId: Long): Boolean =
    content.entities.any { entity ->
        entity.type == EntityType.TEXTMENTION && entity.user?.id == botUserId
    }

private fun hasTargetedBotCommand(content: MessageText, botUsernames: Collection<String>): Boolean {
    val expected = botUsernames.normalizedUsernames()

    return content.entities.any { entity ->
        entity.type == EntityType.BOTCOMMAND &&
            normalizeUsername(content.entitySpan(entity).substringAfter('@', "")) in expected
    }
}
