package com.helltar.vusan.config

data class ChatHistoryConfig(
    val maxRecentInteractions: Int = DEFAULT_MAX_RECENT_INTERACTIONS,
    val maxStoredInteractions: Int = DEFAULT_MAX_STORED_INTERACTIONS,
    val retentionDays: Int = DEFAULT_RETENTION_DAYS
) {
    init {
        require(maxRecentInteractions > 0) { "CHAT_HISTORY_MAX_RECENT_INTERACTIONS must be positive" }
        require(maxStoredInteractions >= maxRecentInteractions) {
            "CHAT_HISTORY_MAX_STORED_INTERACTIONS must be at least CHAT_HISTORY_MAX_RECENT_INTERACTIONS"
        }
        require(retentionDays > 0) { "CHAT_HISTORY_RETENTION_DAYS must be positive" }
    }

    companion object {
        const val DEFAULT_MAX_RECENT_INTERACTIONS = 12
        const val DEFAULT_MAX_STORED_INTERACTIONS = 100
        const val DEFAULT_RETENTION_DAYS = 90
    }
}
