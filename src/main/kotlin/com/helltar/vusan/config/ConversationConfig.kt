package com.helltar.vusan.config

data class ConversationConfig(val retentionDays: Int = DEFAULT_RETENTION_DAYS) {

    init {
        require(retentionDays > 0) { "CONVERSATION_RETENTION_DAYS must be positive" }
    }

    companion object {
        const val DEFAULT_RETENTION_DAYS = 90
    }
}
