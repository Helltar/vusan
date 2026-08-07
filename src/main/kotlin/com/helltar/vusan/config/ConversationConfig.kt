package com.helltar.vusan.config

data class ConversationConfig(
    val maxRecentInteractions: Int = DEFAULT_MAX_RECENT_INTERACTIONS,
    val maxStoredInteractions: Int = DEFAULT_MAX_STORED_INTERACTIONS,
    val retentionDays: Int = DEFAULT_RETENTION_DAYS
) {
    init {
        require(maxRecentInteractions > 0) { "CONVERSATION_MAX_RECENT_INTERACTIONS must be positive" }
        require(maxStoredInteractions >= maxRecentInteractions) {
            "CONVERSATION_MAX_STORED_INTERACTIONS must be at least CONVERSATION_MAX_RECENT_INTERACTIONS"
        }
        require(retentionDays > 0) { "CONVERSATION_RETENTION_DAYS must be positive" }
    }

    companion object {
        // this is the count a recap is triggered by, not what the prompt ends up carrying — a window
        // too small for these still fits only what its token budget allows. Kept well above that
        // budget on a large window, where a low count buys nothing and only pays for recaps.
        const val DEFAULT_MAX_RECENT_INTERACTIONS = 24
        const val DEFAULT_MAX_STORED_INTERACTIONS = 100
        const val DEFAULT_RETENTION_DAYS = 90
    }
}
