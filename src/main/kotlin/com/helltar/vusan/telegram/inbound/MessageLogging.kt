package com.helltar.vusan.telegram.inbound

import com.helltar.vusan.common.collapseWhitespaceAndCap
import io.github.oshai.kotlinlogging.KotlinLogging
import org.telegram.telegrambots.meta.api.objects.message.Message

private const val LOG_TEXT_MAX_CHARS = 300

private val log = KotlinLogging.logger("com.helltar.vusan.telegram.inbound")

internal fun Message.logIncoming() {
    log.debug {
        buildString {
            append("message: chat=$chatIdLong chatType=${promptChatType()} msg=$messageIdLong")
            append(" type=${contentTypeName()}")
            chat.titleOrDisplayName()?.let { append(" chatTitle=[$it]") }
            senderIdOrNull()?.let { append(" user=$it") }
            senderUsernameOrNull()?.let { append(" username=[$it]") }
            senderDisplayNameOrNull()?.let { append(" name=[$it]") }

            sticker?.let { sticker ->
                append(" sticker=[${sticker.readableFormat()} ${sticker.type ?: "regular"}")
                sticker.emoji?.let { emoji -> append(" $emoji") }
                sticker.setName?.let { setName -> append(" set=$setName") }
                append("]")
            }

            textSnippetOrNull()
                ?.collapseWhitespaceAndCap(LOG_TEXT_MAX_CHARS)
                ?.let { append(" text=[$it]") }
        }
    }
}

internal fun Message.logDenied(reason: String) {
    log.warn {
        buildString {
            append("denied ($reason): chat=$chatIdLong user=${senderIdOrNull()} type=${contentTypeName()}")
            senderUsernameOrNull()?.let { append(" username=[$it]") }
            senderDisplayNameOrNull()?.let { append(" name=[$it]") }
            textSnippetOrNull()?.collapseWhitespaceAndCap(LOG_TEXT_MAX_CHARS)?.let { append(" text=[$it]") }
        }
    }
}
