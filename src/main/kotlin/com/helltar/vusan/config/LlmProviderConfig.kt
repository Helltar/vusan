package com.helltar.vusan.config

sealed interface LlmProviderConfig {

    data class OpenAi(val apiKey: String, val model: String) : LlmProviderConfig {
        init {
            require(apiKey.isNotBlank()) { "OPENAI_API_KEY must not be blank" }
            require(model.isNotBlank()) { "OPENAI_MODEL must not be blank" }
        }
    }
}
