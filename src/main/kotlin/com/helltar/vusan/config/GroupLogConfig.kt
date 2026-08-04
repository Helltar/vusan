package com.helltar.vusan.config

data class GroupLogConfig(
    val enabled: Boolean = true,
    val retentionDays: Int = DEFAULT_RETENTION_DAYS,
    val maxMessagesPerChat: Int = DEFAULT_MAX_MESSAGES_PER_CHAT,
    val recentMessages: Int = DEFAULT_RECENT_MESSAGES,
    val recentMinutes: Int = DEFAULT_RECENT_MINUTES
) {
    init {
        require(retentionDays > 0) { "GROUP_LOG_RETENTION_DAYS must be positive" }
        require(maxMessagesPerChat > 0) { "GROUP_LOG_MAX_MESSAGES_PER_CHAT must be positive" }
        require(recentMessages >= 0) { "GROUP_LOG_RECENT_MESSAGES must not be negative" }
        require(recentMinutes > 0) { "GROUP_LOG_RECENT_MINUTES must be positive" }
    }

    /** Whether a group turn should carry the `<recent_chat>` slice of what the chat was just saying. */
    val recentChatEnabled: Boolean
        get() = enabled && recentMessages > 0

    companion object {
        const val DEFAULT_RETENTION_DAYS = 30
        const val DEFAULT_MAX_MESSAGES_PER_CHAT = 20_000
        const val DEFAULT_RECENT_MESSAGES = 15
        const val DEFAULT_RECENT_MINUTES = 60
    }
}
