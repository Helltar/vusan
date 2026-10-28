package com.helltar.vusan.agent.presence

import com.helltar.vusan.agent.grouplog.GroupLogEntry
import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.request.testChat
import java.time.Instant

internal val CHAT: ChatRef = testChat(-100)

internal fun person(
    messageId: String,
    text: String,
    at: Instant,
    name: String = "Alice",
    senderId: String = "7",
    chat: ChatRef = CHAT,
) =
    GroupLogEntry(
        chat = chat,
        messageId = messageId,
        kind = "text",
        sentAt = at,
        senderId = senderId,
        senderName = name,
        text = text,
    )

internal fun bot(text: String, at: Instant, chat: ChatRef = CHAT) =
    GroupLogEntry(chat = chat, messageId = null, kind = GroupLogEntry.BOT_KIND, sentAt = at, text = text)
