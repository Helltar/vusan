package com.helltar.vusan.telegram.inbound

import com.helltar.vusan.telegram.inbound.realReplyOrNull
import com.helltar.vusan.agent.addressing.AmbientCandidate
import com.helltar.vusan.telegram.telegramChat
import com.helltar.vusan.telegram.telegramUser
import org.telegram.telegrambots.meta.api.objects.message.Message

/**
 * The message as ambient addressing reads it, or `null` when it is not one to ask about.
 *
 * Only what a person typed into a group: a text or a caption, edited or not. Never a forward, a command,
 * anything from another bot or a chat's own account (a channel, an anonymous admin), nor a voice message,
 * which would cost a transcription of every one. [captionSource] is the album part carrying the caption when
 * the message anchors a media group.
 */
internal fun Message.ambientCandidateOrNull(captionSource: Message, authorWaiting: Boolean): AmbientCandidate? {
    // a private chat is always answered, and a forward is not something said to the chat just now
    if (isPrivateChat || forwardOrigin != null) return null

    // posted under a chat's account or through an inline bot, not typed by a person
    if (senderChat != null || viaBot != null) return null

    val sender = from?.takeUnless { it.isBot == true } ?: return null
    val content = captionSource.messageTextOrNull()?.takeUnless(::isBotCommand) ?: return null

    return AmbientCandidate(
        chat = telegramChat(chatIdLong),
        // the part carrying the words: every album part has its own group log line, and the classifier must
        // not see the caption twice
        messageId = captionSource.messageIdLong.toString(),
        author = senderDisplayNameOrNull() ?: sender.userName ?: sender.id.toString(),
        sender = telegramUser(sender.id),
        text = content.text.trim().takeIf { it.isNotEmpty() } ?: return null,
        inReplyTo = repliedAuthorOrNull(),
        authorWaiting = authorWaiting,
        edited = editDate != null,
    )
}

private fun Message.repliedAuthorOrNull(): String? {
    val replied = realReplyOrNull ?: return null

    return replied.from?.let { displayName(it.firstName, it.lastName) ?: it.userName }
        ?: replied.senderChat?.title
}
