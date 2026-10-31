package com.helltar.vusan.config.llm

import com.helltar.vusan.llm.ReasoningEffort
import com.helltar.vusan.llm.openai.OpenAiEndpoint
import com.helltar.vusan.llm.codex.defaultCodexAuthFile
import java.nio.file.Path
import kotlin.time.Duration

/** The serving tiers OpenAI's Responses API takes; `default` means none was chosen. */
enum class ServiceTier {
    AUTO,
    DEFAULT,
    FLEX,
    SCALE,
    PRIORITY,
    FAST,
    ULTRAFAST;

    /** The value a request carries, also the id the Codex model catalog uses. */
    val requestValue: String
        get() = name.lowercase()
}

/**
 * One model and how to reach it, as configured. The chat model, its fallback, the vision model and the
 * addressing model are each one, read from their own variable prefix.
 *
 * [seesImages] and [efforts] are what the vendor said of the model at startup, so a role reads facts instead
 * of asking which provider it is on. `null` means nobody said, and each provider's runtime fills in its own
 * assumption.
 */
sealed interface LlmProviderConfig {

    val model: String
    val reasoningEffort: ReasoningEffort?

    // caps how long one call may hang before the agent replies with an error, instead of the engine's
    // quarter-hour default.
    val requestTimeout: Duration
    val contextWindowTokens: Long?
    val seesImages: Boolean?
    val efforts: Set<ReasoningEffort>?

    /** The variable prefix this was read from, `LLM` or a role's, for startup errors. */
    val envPrefix: String

    /**
     * This configuration pointed at [model], for a role on the chat provider with its own model. The chat
     * model's vendor facts are dropped, and a role plans no history, so it has no window.
     */
    fun withModel(model: String, reasoningEffort: ReasoningEffort?, envPrefix: String): LlmProviderConfig

    /**
     * This configuration with calls cut off at [requestTimeout], for a role whose late answer is worthless.
     */
    fun withRequestTimeout(requestTimeout: Duration): LlmProviderConfig

    data class OpenAi(
        val apiKey: String,
        override val model: String,
        override val reasoningEffort: ReasoningEffort? = null,
        override val requestTimeout: Duration,
        override val contextWindowTokens: Long? = null,
        override val seesImages: Boolean? = null,
        override val efforts: Set<ReasoningEffort>? = null,
        override val envPrefix: String = DEFAULT_ENV_PREFIX,
    ) : LlmProviderConfig {

        init {
            require(apiKey.isNotBlank()) { "the api key must not be blank" }
            require(model.isNotBlank()) { "the model must not be blank" }
            requireSane(requestTimeout, contextWindowTokens)
        }

        override fun withModel(model: String, reasoningEffort: ReasoningEffort?, envPrefix: String): OpenAi =
            copy(
                model = model,
                reasoningEffort = reasoningEffort,
                contextWindowTokens = null,
                seesImages = null,
                efforts = null,
                envPrefix = envPrefix,
            )

        override fun withRequestTimeout(requestTimeout: Duration): OpenAi = copy(requestTimeout = requestTimeout)
    }

    /**
     * [maxOutputTokens] is what the vendor's model list said at startup; a configuration built without asking
     * uses the runtime's default.
     */
    data class Anthropic(
        val apiKey: String,
        override val model: String,
        override val reasoningEffort: ReasoningEffort? = null,
        override val requestTimeout: Duration,
        override val contextWindowTokens: Long? = null,
        val maxOutputTokens: Int? = null,
        override val seesImages: Boolean? = null,
        override val efforts: Set<ReasoningEffort>? = null,
        override val envPrefix: String = DEFAULT_ENV_PREFIX,
    ) : LlmProviderConfig {

        init {
            require(apiKey.isNotBlank()) { "the api key must not be blank" }
            require(model.isNotBlank()) { "the model must not be blank" }
            requireSane(requestTimeout, contextWindowTokens)
        }

        override fun withModel(model: String, reasoningEffort: ReasoningEffort?, envPrefix: String): Anthropic =
            copy(
                model = model,
                reasoningEffort = reasoningEffort,
                contextWindowTokens = null,
                maxOutputTokens = null,
                seesImages = null,
                efforts = null,
                envPrefix = envPrefix,
            )

        override fun withRequestTimeout(requestTimeout: Duration): Anthropic = copy(requestTimeout = requestTimeout)
    }

    /**
     * Any server speaking the OpenAI API: a local one, or a vendor without its own provider. It may serve
     * anything, so [seesImages] is what its model list said at startup (`true`, `false` or `null`), and a
     * chat model sees only on `true`; otherwise it needs a vision model.
     */
    data class OpenAiCompatible(
        val baseUrl: String,
        val apiKey: String,
        override val model: String,
        val endpoint: OpenAiEndpoint = OpenAiEndpoint.COMPLETIONS,
        override val reasoningEffort: ReasoningEffort? = null,
        override val requestTimeout: Duration,
        override val contextWindowTokens: Long? = null,
        override val seesImages: Boolean? = null,
        override val efforts: Set<ReasoningEffort>? = null,
        override val envPrefix: String = DEFAULT_ENV_PREFIX,
    ) : LlmProviderConfig {

        init {
            require(baseUrl.isNotBlank()) { "the base url must not be blank" }
            require(apiKey.isNotBlank()) { "the api key must not be blank (use any non-empty value if the server ignores it)" }
            require(model.isNotBlank()) { "the model must not be blank" }
            requireSane(requestTimeout, contextWindowTokens)
        }

        override fun withModel(model: String, reasoningEffort: ReasoningEffort?, envPrefix: String): OpenAiCompatible =
            copy(
                model = model,
                reasoningEffort = reasoningEffort,
                contextWindowTokens = null,
                seesImages = null,
                efforts = null,
                envPrefix = envPrefix,
            )

        override fun withRequestTimeout(requestTimeout: Duration): OpenAiCompatible = copy(requestTimeout = requestTimeout)
    }

    /**
     * A ChatGPT subscription through the credentials `codex login` writes. No `apiKey` on purpose: the bearer
     * token is resolved per request from `~/.codex/auth.json`, since it expires and rotates behind our back.
     * [seesImages] and [efforts] come from the plan's catalog entry, filled in by the preflight.
     */
    data class Codex(
        override val model: String,
        override val reasoningEffort: ReasoningEffort? = null,
        // the plan's faster serving tier, which spends the same allowance quicker, so it is opt-in.
        val serviceTier: ServiceTier? = null,
        // the catalog's default verbosity, sent as the CLI sends it; the backend's own default is a step
        // wordier.
        val verbosity: String? = null,
        // whether the plan may also answer web searches, from the same allowance as turns
        val webSearch: Boolean = true,
        override val seesImages: Boolean? = null,
        override val efforts: Set<ReasoningEffort>? = null,
        // the Codex CLI version reported to the backend, which decides how much of the catalog it returns.
        // `null` claims the newest of this build's floor, the installed CLI and the latest release.
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

        // the serving tier is the chat's: a role spends the same allowance, and its model may not be served
        // at that tier
        override fun withModel(model: String, reasoningEffort: ReasoningEffort?, envPrefix: String): Codex =
            copy(
                model = model,
                reasoningEffort = reasoningEffort,
                contextWindowTokens = null,
                serviceTier = null,
                verbosity = null,
                seesImages = null,
                efforts = null,
                envPrefix = envPrefix,
            )

        override fun withRequestTimeout(requestTimeout: Duration): Codex = copy(requestTimeout = requestTimeout)
    }

    /**
     * xAI's API with an xAI key. The window, vision and efforts are what its two model lists said at startup.
     */
    data class Xai(
        val apiKey: String,
        override val model: String,
        override val reasoningEffort: ReasoningEffort? = null,
        override val requestTimeout: Duration,
        override val contextWindowTokens: Long? = null,
        override val seesImages: Boolean? = null,
        override val efforts: Set<ReasoningEffort>? = null,
        override val envPrefix: String = DEFAULT_ENV_PREFIX,
    ) : LlmProviderConfig {

        init {
            require(apiKey.isNotBlank()) { "the api key must not be blank" }
            require(model.isNotBlank()) { "the model must not be blank" }
            requireSane(requestTimeout, contextWindowTokens)
        }

        override fun withModel(model: String, reasoningEffort: ReasoningEffort?, envPrefix: String): Xai =
            copy(
                model = model,
                reasoningEffort = reasoningEffort,
                contextWindowTokens = null,
                seesImages = null,
                efforts = null,
                envPrefix = envPrefix,
            )

        override fun withRequestTimeout(requestTimeout: Duration): Xai = copy(requestTimeout = requestTimeout)
    }

    /**
     * DeepSeek's API with a DeepSeek key. The window, [maxOutputTokens], vision and efforts are what its
     * model list said at startup.
     */
    data class DeepSeek(
        val apiKey: String,
        override val model: String,
        override val reasoningEffort: ReasoningEffort? = null,
        override val requestTimeout: Duration,
        override val contextWindowTokens: Long? = null,
        val maxOutputTokens: Int? = null,
        override val seesImages: Boolean? = null,
        override val efforts: Set<ReasoningEffort>? = null,
        override val envPrefix: String = DEFAULT_ENV_PREFIX,
    ) : LlmProviderConfig {

        init {
            require(apiKey.isNotBlank()) { "the api key must not be blank" }
            require(model.isNotBlank()) { "the model must not be blank" }
            requireSane(requestTimeout, contextWindowTokens)
        }

        override fun withModel(model: String, reasoningEffort: ReasoningEffort?, envPrefix: String): DeepSeek =
            copy(
                model = model,
                reasoningEffort = reasoningEffort,
                contextWindowTokens = null,
                maxOutputTokens = null,
                seesImages = null,
                efforts = null,
                envPrefix = envPrefix,
            )

        override fun withRequestTimeout(requestTimeout: Duration): DeepSeek = copy(requestTimeout = requestTimeout)
    }

    /**
     * Google's Gemini API with an AI Studio key, the model named as the API lists it
     * (`gemini-3.5-flash-lite`). The window and [maxOutputTokens] come from its model list at startup, which
     * says nothing of images and of efforts only whether the model thinks.
     */
    data class Google(
        val apiKey: String,
        override val model: String,
        override val reasoningEffort: ReasoningEffort? = null,
        override val requestTimeout: Duration,
        override val contextWindowTokens: Long? = null,
        val maxOutputTokens: Int? = null,
        override val seesImages: Boolean? = null,
        override val efforts: Set<ReasoningEffort>? = null,
        override val envPrefix: String = DEFAULT_ENV_PREFIX,
    ) : LlmProviderConfig {

        init {
            require(apiKey.isNotBlank()) { "the api key must not be blank" }
            require(model.isNotBlank()) { "the model must not be blank" }
            requireSane(requestTimeout, contextWindowTokens)
        }

        override fun withModel(model: String, reasoningEffort: ReasoningEffort?, envPrefix: String): Google =
            copy(
                model = model,
                reasoningEffort = reasoningEffort,
                contextWindowTokens = null,
                maxOutputTokens = null,
                seesImages = null,
                efforts = null,
                envPrefix = envPrefix,
            )

        override fun withRequestTimeout(requestTimeout: Duration): Google = copy(requestTimeout = requestTimeout)
    }

    /**
     * OpenRouter with an OpenRouter key, the model named as OpenRouter lists it
     * (`anthropic/claude-opus-5.5`). [maxOutputTokens], the window, vision and efforts are what its model
     * list said at startup.
     */
    data class OpenRouter(
        val apiKey: String,
        override val model: String,
        override val reasoningEffort: ReasoningEffort? = null,
        override val requestTimeout: Duration,
        override val contextWindowTokens: Long? = null,
        val maxOutputTokens: Int? = null,
        override val seesImages: Boolean? = null,
        override val efforts: Set<ReasoningEffort>? = null,
        override val envPrefix: String = DEFAULT_ENV_PREFIX,
    ) : LlmProviderConfig {

        init {
            require(apiKey.isNotBlank()) { "the api key must not be blank" }
            require(model.isNotBlank()) { "the model must not be blank" }
            requireSane(requestTimeout, contextWindowTokens)
        }

        override fun withModel(model: String, reasoningEffort: ReasoningEffort?, envPrefix: String): OpenRouter =
            copy(
                model = model,
                reasoningEffort = reasoningEffort,
                contextWindowTokens = null,
                maxOutputTokens = null,
                seesImages = null,
                efforts = null,
                envPrefix = envPrefix,
            )

        override fun withRequestTimeout(requestTimeout: Duration): OpenRouter = copy(requestTimeout = requestTimeout)
    }
}

private const val DEFAULT_ENV_PREFIX = "LLM"

private fun requireSane(requestTimeout: Duration, contextWindowTokens: Long?) {
    require(requestTimeout.isPositive()) { "the request timeout must be positive" }
    require(contextWindowTokens == null || contextWindowTokens > 0L) { "the context window must be positive" }
}
