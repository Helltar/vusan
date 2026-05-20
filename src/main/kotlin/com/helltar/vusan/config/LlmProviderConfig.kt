package com.helltar.vusan.config

sealed interface LlmProviderConfig {

    data class OpenAi(val apiKey: String, val model: String) : LlmProviderConfig {
        init {
            require(apiKey.isNotBlank()) { "OPENAI_API_KEY must not be blank" }
            require(model.isNotBlank()) { "OPENAI_MODEL must not be blank" }
        }
    }

    data class Ollama(val baseUrl: String, val model: String) : LlmProviderConfig {
        init {
            require(baseUrl.isNotBlank()) { "OLLAMA_BASE_URL must not be blank" }
            require(model.isNotBlank()) { "OLLAMA_MODEL must not be blank" }
        }
    }

    data class OpenAiCompatible(val baseUrl: String, val apiKey: String, val model: String) : LlmProviderConfig {
        init {
            require(baseUrl.isNotBlank()) { "OPENAI_BASE_URL must not be blank" }
            require(apiKey.isNotBlank()) { "OPENAI_API_KEY must not be blank (use any non-empty value if the local server ignores it)" }
            require(model.isNotBlank()) { "OPENAI_MODEL must not be blank" }
        }
    }
}
