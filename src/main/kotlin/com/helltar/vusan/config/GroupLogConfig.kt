package com.helltar.vusan.config

data class GroupLogConfig(
    val enabled: Boolean = true,
    val retentionDays: Int = DEFAULT_RETENTION_DAYS,
    // a ceiling for the chat that outruns retention, not a setting: a thousand messages a day still
    // keeps the whole month, and only a chat well past that is trimmed by count instead of by age.
    val maxMessagesPerChat: Int = DEFAULT_MAX_MESSAGES_PER_CHAT,
) {

    init {
        require(retentionDays > 0) { "GROUP_LOG_RETENTION_DAYS must be positive" }
        require(maxMessagesPerChat > 0) { "maxMessagesPerChat must be positive" }
    }

    companion object {
        const val DEFAULT_RETENTION_DAYS = 30
        const val DEFAULT_MAX_MESSAGES_PER_CHAT = 50_000
    }
}
