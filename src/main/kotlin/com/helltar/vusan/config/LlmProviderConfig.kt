package com.helltar.vusan.config

sealed interface LlmProviderConfig {

    data class OpenAi(val apiKey: String, val model: String) : LlmProviderConfig {
        init {
            require(apiKey.isNotBlank()) { "LLM_API_KEY must not be blank" }
            require(model.isNotBlank()) { "LLM_MODEL must not be blank" }
        }
    }

    data class OpenAiCompatible(val baseUrl: String, val apiKey: String, val model: String) : LlmProviderConfig {
        init {
            require(baseUrl.isNotBlank()) { "LLM_BASE_URL must not be blank" }
            require(apiKey.isNotBlank()) { "LLM_API_KEY must not be blank (use any non-empty value if the local server ignores it)" }
            require(model.isNotBlank()) { "LLM_MODEL must not be blank" }
        }
    }
}
