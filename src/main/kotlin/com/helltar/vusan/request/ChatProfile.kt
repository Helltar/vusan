package com.helltar.vusan.request

/**
 * What a turn needs to know about the chat it answers in, beyond the message that triggered it.
 *
 * This is the part an adapter has to go and ask the platform for, which is why it is a record of its
 * own: it is looked up and cached separately, and a scheduled task — with no message behind it at all —
 * has nothing else to build a [ChatContext] from.
 */
data class ChatProfile(
    val description: String? = null,
    val capabilities: ChatCapabilities = ChatCapabilities.UNRESTRICTED
) {

    companion object {
        /** Nothing known and nothing restricted — a private chat, or a lookup that was skipped. */
        val NONE = ChatProfile()
    }
}
