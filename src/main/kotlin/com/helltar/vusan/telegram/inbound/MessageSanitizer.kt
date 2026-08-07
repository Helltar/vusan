package com.helltar.vusan.telegram.inbound

import com.helltar.vusan.agent.neutralizePromptBlocks
import org.telegram.telegrambots.meta.api.objects.EntityType
import org.telegram.telegrambots.meta.api.objects.MessageEntity

// text or caption of an incoming message together with its formatting entities.
internal data class MessageText(val text: String, val entities: List<MessageEntity>)

// entity offsets and lengths are utf-16 code units, which is exactly how kotlin indexes strings;
// bounds are still clamped because entities arrive from outside the process.
internal fun MessageText.entitySpan(entity: MessageEntity): String {
    val start = entity.offset.coerceIn(0, text.length)
    val end = (entity.offset + entity.length).coerceIn(start, text.length)
    return text.substring(start, end)
}

internal fun sanitizeUserText(content: MessageText, botUserId: Long, botUsername: String?): String {
    val expectedUsername = normalizeUsername(botUsername)

    val removals =
        content.entities
            .filter { content.isBotMention(it, botUserId, expectedUsername) }
            .sortedBy { it.offset }

    if (removals.isEmpty()) return content.text.trim().neutralizePromptBlocks()

    val sanitized =
        buildString {
            var position = 0

            removals.forEach { entity ->
                val start = entity.offset.coerceIn(position, content.text.length)
                append(content.text, position, start)
                position = (entity.offset + entity.length).coerceIn(start, content.text.length)
            }

            append(content.text, position, content.text.length)
        }

    return sanitized.cleanupAfterMentionRemoval().neutralizePromptBlocks()
}

private fun MessageText.isBotMention(entity: MessageEntity, botUserId: Long, expectedUsername: String?): Boolean =
    when (entity.type) {
        EntityType.MENTION -> expectedUsername != null && normalizeUsername(entitySpan(entity)) == expectedUsername
        EntityType.TEXTMENTION -> entity.user?.id == botUserId
        else -> false
    }

private fun String.cleanupAfterMentionRemoval(): String =
    replace(Regex("[\\t ]+([,.;:!?])"), "$1")
        .replace(Regex("(^|\\n)[\\t ]*[,.;:!?-]+[\\t ]*"), "$1")
        .replace(Regex("[\\t ]{2,}"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .trim()
