package com.helltar.vusan.request

/**
 * What a turn needs to know about its chat beyond the triggering message: the part an adapter must ask the
 * platform for, so it is its own record, looked up and cached separately, and all a scheduled task has to
 * build a [ChatContext] from.
 */
data class ChatProfile(
    val description: String? = null,
    val capabilities: ChatCapabilities = ChatCapabilities.UNRESTRICTED,
) {

    companion object {
        /** Nothing known and nothing restricted: a private chat, or a skipped lookup. */
        val NONE = ChatProfile()
    }
}
