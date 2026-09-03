package com.helltar.vusan.config

import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.base.models.ServiceTier
import java.nio.file.Path
import kotlin.time.Duration

enum class HostedLlmProvider {
    OPENAI,
    ANTHROPIC,
    GOOGLE,
    DEEPSEEK
}

/** Which OpenAI HTTP API a request goes to: `/v1/chat/completions` or `/v1/responses`. */
enum class OpenAiEndpoint {
    COMPLETIONS,
    RESPONSES
}

sealed interface LlmProviderConfig {

    // caps how long a single LLM HTTP call may hang before it fails and the agent surfaces an error
    // reply, instead of waiting out the provider client's 15-minute default while the bot stays silent.
    val requestTimeout: Duration
    val contextWindowTokens: Long?

    data class Hosted(
        val provider: HostedLlmProvider,
        val apiKey: String,
        val model: String,
        override val requestTimeout: Duration,
        override val contextWindowTokens: Long? = null
    ) : LlmProviderConfig {
        init {
            require(apiKey.isNotBlank()) { "LLM_API_KEY must not be blank" }
            require(model.isNotBlank()) { "LLM_MODEL must not be blank" }
            require(requestTimeout.isPositive()) { "LLM_REQUEST_TIMEOUT_SECONDS must be positive" }
            require(contextWindowTokens == null || contextWindowTokens > 0L) {
                "LLM_CONTEXT_WINDOW_TOKENS must be positive"
            }
        }
    }

    /**
     * A ChatGPT subscription reached through the credentials `codex login` writes, instead of an
     * API key. There is no `apiKey` here on purpose: the bearer token is resolved per request from
     * `~/.codex/auth.json`, because it expires and is rotated behind our back.
     */
    data class Codex(
        val model: String,
        val reasoningEffort: ReasoningEffort? = null,
        // the plan's faster serving tier. it is not free: the same allowance is spent quicker, so it stays
        // off unless the operator asks for it.
        val serviceTier: ServiceTier? = null,
        val supportsVision: Boolean = true,
        val authFile: Path = defaultCodexAuthFile(),
        override val requestTimeout: Duration,
        override val contextWindowTokens: Long? = null
    ) : LlmProviderConfig {
        init {
            require(model.isNotBlank()) { "LLM_MODEL must not be blank" }
            require(requestTimeout.isPositive()) { "LLM_REQUEST_TIMEOUT_SECONDS must be positive" }
            require(contextWindowTokens == null || contextWindowTokens > 0L) {
                "LLM_CONTEXT_WINDOW_TOKENS must be positive"
            }
        }
    }

    data class OpenAiCompatible(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val endpoint: OpenAiEndpoint = OpenAiEndpoint.COMPLETIONS,
        val reasoningEffort: ReasoningEffort? = null,
        override val requestTimeout: Duration,
        override val contextWindowTokens: Long? = null
    ) : LlmProviderConfig {
        init {
            require(baseUrl.isNotBlank()) { "LLM_BASE_URL must not be blank" }
            require(apiKey.isNotBlank()) { "LLM_API_KEY must not be blank (use any non-empty value if the local server ignores it)" }
            require(model.isNotBlank()) { "LLM_MODEL must not be blank" }
            require(requestTimeout.isPositive()) { "LLM_REQUEST_TIMEOUT_SECONDS must be positive" }
            require(contextWindowTokens == null || contextWindowTokens > 0L) {
                "LLM_CONTEXT_WINDOW_TOKENS must be positive"
            }
        }
    }
}
