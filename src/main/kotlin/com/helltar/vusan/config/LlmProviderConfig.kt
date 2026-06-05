package com.helltar.vusan.config

import kotlin.time.Duration

sealed interface LlmProviderConfig {

    // Caps how long a single LLM HTTP call may hang before it fails and the agent surfaces an error
    // reply, instead of waiting out the provider client's 15-minute default while the bot stays silent.
    val requestTimeout: Duration

    data class OpenAi(val apiKey: String, val model: String, override val requestTimeout: Duration) : LlmProviderConfig {
        init {
            require(apiKey.isNotBlank()) { "LLM_API_KEY must not be blank" }
            require(model.isNotBlank()) { "LLM_MODEL must not be blank" }
            require(requestTimeout.isPositive()) { "LLM_REQUEST_TIMEOUT_SECONDS must be positive" }
        }
    }

    data class OpenAiCompatible(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        override val requestTimeout: Duration
    ) : LlmProviderConfig {
        init {
            require(baseUrl.isNotBlank()) { "LLM_BASE_URL must not be blank" }
            require(apiKey.isNotBlank()) { "LLM_API_KEY must not be blank (use any non-empty value if the local server ignores it)" }
            require(model.isNotBlank()) { "LLM_MODEL must not be blank" }
            require(requestTimeout.isPositive()) { "LLM_REQUEST_TIMEOUT_SECONDS must be positive" }
        }
    }
}
