package com.helltar.vusan.config

import com.helltar.vusan.llm.ReasoningEffort
import com.helltar.vusan.llm.openai.OpenAiEndpoint
import java.nio.file.Path
import kotlin.time.Duration

/** The serving tiers OpenAI's Responses API takes; `default` means none was chosen. */
enum class ServiceTier {
    AUTO,
    DEFAULT,
    FLEX,
    SCALE,
    PRIORITY;

    /** The value a request carries, which is also the id the Codex model catalog lists it under. */
    val requestValue: String
        get() = name.lowercase()
}

/**
 * One model and how to reach it, as a deployment configured it. The chat model, its fallback, the
 * vision model and the addressing model are each one of these, read from their own variable prefix.
 */
sealed interface LlmProviderConfig {

    val model: String
    val reasoningEffort: ReasoningEffort?

    // caps how long a single LLM HTTP call may hang before it fails and the agent surfaces an error
    // reply, instead of waiting out the engine's quarter-hour default while the bot stays silent.
    val requestTimeout: Duration
    val contextWindowTokens: Long?

    /** The prefix of the variables this was read from, `LLM` or a role's, which a startup error names. */
    val envPrefix: String

    /** This configuration pointed at [model], for a role that runs on the chat provider with a model of its own. */
    fun withModel(model: String, reasoningEffort: ReasoningEffort?, contextWindowTokens: Long?, envPrefix: String): LlmProviderConfig

    data class OpenAi(
        val apiKey: String,
        override val model: String,
        override val reasoningEffort: ReasoningEffort? = null,
        override val requestTimeout: Duration,
        override val contextWindowTokens: Long? = null,
        override val envPrefix: String = DEFAULT_ENV_PREFIX,
    ) : LlmProviderConfig {

        init {
            require(apiKey.isNotBlank()) { "the api key must not be blank" }
            require(model.isNotBlank()) { "the model must not be blank" }
            requireSane(requestTimeout, contextWindowTokens)
        }

        override fun withModel(model: String, reasoningEffort: ReasoningEffort?, contextWindowTokens: Long?, envPrefix: String): OpenAi =
            copy(model = model, reasoningEffort = reasoningEffort, contextWindowTokens = contextWindowTokens, envPrefix = envPrefix)
    }

    /**
     * [maxOutputTokens] and [takesEffort] are what the vendor's model list said at startup, when it
     * answered; a configuration built without asking leaves them to the runtime's defaults.
     */
    data class Anthropic(
        val apiKey: String,
        override val model: String,
        override val reasoningEffort: ReasoningEffort? = null,
        override val requestTimeout: Duration,
        override val contextWindowTokens: Long? = null,
        val maxOutputTokens: Int? = null,
        val takesEffort: Boolean? = null,
        override val envPrefix: String = DEFAULT_ENV_PREFIX,
    ) : LlmProviderConfig {

        init {
            require(apiKey.isNotBlank()) { "the api key must not be blank" }
            require(model.isNotBlank()) { "the model must not be blank" }
            requireSane(requestTimeout, contextWindowTokens)
        }

        override fun withModel(model: String, reasoningEffort: ReasoningEffort?, contextWindowTokens: Long?, envPrefix: String): Anthropic =
            copy(
                model = model,
                reasoningEffort = reasoningEffort,
                contextWindowTokens = contextWindowTokens,
                maxOutputTokens = null,
                takesEffort = null,
                envPrefix = envPrefix,
            )
    }

    /**
     * Any server that speaks the OpenAI API, DeepSeek included. It never claims vision on its own: the
     * server behind [baseUrl] may serve anything, so a chat model here needs a vision model of its own.
     */
    data class OpenAiCompatible(
        val baseUrl: String,
        val apiKey: String,
        override val model: String,
        val endpoint: OpenAiEndpoint = OpenAiEndpoint.COMPLETIONS,
        override val reasoningEffort: ReasoningEffort? = null,
        override val requestTimeout: Duration,
        override val contextWindowTokens: Long? = null,
        override val envPrefix: String = DEFAULT_ENV_PREFIX,
    ) : LlmProviderConfig {

        init {
            require(baseUrl.isNotBlank()) { "the base url must not be blank" }
            require(apiKey.isNotBlank()) { "the api key must not be blank (use any non-empty value if the server ignores it)" }
            require(model.isNotBlank()) { "the model must not be blank" }
            requireSane(requestTimeout, contextWindowTokens)
        }

        override fun withModel(model: String, reasoningEffort: ReasoningEffort?, contextWindowTokens: Long?, envPrefix: String): OpenAiCompatible =
            copy(model = model, reasoningEffort = reasoningEffort, contextWindowTokens = contextWindowTokens, envPrefix = envPrefix)
    }

    /**
     * A ChatGPT subscription reached through the credentials `codex login` writes, instead of an
     * API key. There is no `apiKey` here on purpose: the bearer token is resolved per request from
     * `~/.codex/auth.json`, because it expires and is rotated behind our back.
     */
    data class Codex(
        override val model: String,
        override val reasoningEffort: ReasoningEffort? = null,
        // the plan's faster serving tier. it is not free: the same allowance is spent quicker, so it stays
        // off unless the operator asks for it.
        val serviceTier: ServiceTier? = null,
        // how wordy the catalog says this model should be by default. the backend's own default is a step
        // wordier than what the CLI asks for, so the catalog's value is sent the way the CLI sends it.
        val verbosity: String? = null,
        // whether image generation may run on the plan when no OPENAI_IMAGE_API_KEY is set. every other
        // route waits for its key, so this is the one that has to be switched off rather than left unset.
        val imageGeneration: Boolean = true,
        // whether the plan may also answer a web search, which draws on the same allowance as the turns
        val webSearch: Boolean = true,
        val supportsVision: Boolean = true,
        // the efforts the catalog lists for the model, when it lists any
        val supportedEfforts: Set<ReasoningEffort>? = null,
        // the Codex CLI version reported to the backend, which decides how much of the model catalog it
        // answers with. `null` leaves that to the installed CLI, or to this build's floor without one.
        val clientVersion: String? = null,
        val authFile: Path = defaultCodexAuthFile(),
        override val requestTimeout: Duration,
        override val contextWindowTokens: Long? = null,
        override val envPrefix: String = DEFAULT_ENV_PREFIX,
    ) : LlmProviderConfig {

        init {
            require(model.isNotBlank()) { "the model must not be blank" }
            requireSane(requestTimeout, contextWindowTokens)
        }

        // the serving tier is the chat's: a role spends the same allowance, and its model may not be served at it
        override fun withModel(model: String, reasoningEffort: ReasoningEffort?, contextWindowTokens: Long?, envPrefix: String): Codex =
            copy(
                model = model,
                reasoningEffort = reasoningEffort,
                contextWindowTokens = contextWindowTokens,
                serviceTier = null,
                verbosity = null,
                supportedEfforts = null,
                envPrefix = envPrefix,
            )
    }
}

private const val DEFAULT_ENV_PREFIX = "LLM"

private fun requireSane(requestTimeout: Duration, contextWindowTokens: Long?) {
    require(requestTimeout.isPositive()) { "the request timeout must be positive" }
    require(contextWindowTokens == null || contextWindowTokens > 0L) { "the context window must be positive" }
}
