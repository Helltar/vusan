package com.helltar.vusan.telegram.inbound

import com.helltar.vusan.telegram.inbound.realReplyOrNull
import com.helltar.vusan.agent.addressing.AmbientCandidate
import com.helltar.vusan.telegram.telegramChat
import org.telegram.telegrambots.meta.api.objects.message.Message

/**
 * The message as ambient addressing reads it, or `null` when it is not one to ask about at all.
 *
 * Only what a person typed into a group qualifies: a text, or the caption on what they sent. An edit, a
 * forward, a command, and anything posted by another bot or under a chat's own account (a channel, an
 * anonymous admin) never does — and nor does a voice message, since finding out whether it is meant for
 * the bot would cost a transcription of every one. [captionSource] is the album part carrying the caption
 * when the message anchors a media group.
 */
internal fun Message.ambientCandidateOrNull(captionSource: Message, authorWaiting: Boolean): AmbientCandidate? {
    // a private chat is always answered, and an edit or a forward is not something said to the chat just now
    if (isPrivateChat || editDate != null || forwardOrigin != null) return null

    // posted under a chat's own account or through an inline bot rather than typed by a person
    if (senderChat != null || viaBot != null) return null

    val sender = from?.takeUnless { it.isBot == true } ?: return null
    val content = captionSource.messageTextOrNull()?.takeUnless(::isBotCommand) ?: return null

    return AmbientCandidate(
        chat = telegramChat(chatIdLong),
        // the part the words are on: every part of an album has its own line in the group log, and the
        // classifier must not be shown the caption twice, once as the message and once as the chat before it
        messageId = captionSource.messageIdLong.toString(),
        author = senderDisplayNameOrNull() ?: sender.userName ?: sender.id.toString(),
        text = content.text.trim().takeIf { it.isNotEmpty() } ?: return null,
        inReplyTo = repliedAuthorOrNull(),
        authorWaiting = authorWaiting,
    )
}

private fun Message.repliedAuthorOrNull(): String? {
    val replied = realReplyOrNull ?: return null

    return replied.from?.let { displayName(it.firstName, it.lastName) ?: it.userName }
        ?: replied.senderChat?.title
}
