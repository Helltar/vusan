package com.helltar.vusan.config

import com.helltar.vusan.agent.ContextWindowPolicy
import com.helltar.vusan.llm.LlmClient
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.LlmProvider
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.RetryingLlmClient
import com.helltar.vusan.llm.anthropic.ANTHROPIC_EFFORTS
import com.helltar.vusan.llm.anthropic.AnthropicClient
import com.helltar.vusan.llm.llmHttpClient
import com.helltar.vusan.llm.openai.OpenAiClient
import com.helltar.vusan.llm.openai.OpenAiEndpoint
import io.ktor.client.*

private const val OPENAI_API_BASE_URL = "https://api.openai.com"
private const val OPENAI_API_PATH = "/v1"

// what every openai model since gpt-5.5 lists on its model page (checked 2026-09-17 for the 5.6 and 6
// generations); openai's model list reports no window, so this is assumed and LLM_CONTEXT_WINDOW_TOKENS
// says otherwise when a model does not keep it.
private const val OPENAI_CONTEXT_WINDOW = 1_050_000L
private const val OPENAI_MAX_OUTPUT = 128_000

// what every claude model since opus 4.7 lists, and what the vendor's model list confirms at startup
private const val ANTHROPIC_CONTEXT_WINDOW = 1_000_000L
private const val ANTHROPIC_MAX_OUTPUT = 128_000

private const val PROMPT_CACHE_KEY = "vusan"
private const val COMPACTION_CACHE_KEY = "vusan-recap"

/**
 * One configured model, ready to call: the client, the model it is for, and the options a turn and a
 * history recap send with it. The recap gets no prompt caching and its own cache key — its body never
 * repeats, so a cache write would buy a read nobody makes, and a shared key would dilute the chat's.
 */
data class LlmRuntime(
    val providerLabel: String,
    val client: LlmClient,
    val model: LlmModel,
    val chatOptions: RequestOptions,
    val compactionOptions: RequestOptions = chatOptions.copy(cachePrompt = false),
)

/** The effort every chat call carries, as the request spells it, or `null` for the model's own default. */
val LlmRuntime.reasoningEffort: String?
    get() = chatOptions.reasoningEffort?.requestValue

/** The serving tier every chat call asks for, or `null` for the standard one. */
val LlmRuntime.serviceTier: String?
    get() = chatOptions.serviceTier

/**
 * Builds the runtime for [config]. [http] is for tests, which hand in a client over a mock engine; a
 * deployment leaves it to the resolver, which gives a Codex runtime the client that signs its requests.
 */
fun resolveLlmRuntime(config: LlmProviderConfig, codexAuth: CodexAuthStore? = null, http: HttpClient? = null): LlmRuntime =
    when (config) {
        is LlmProviderConfig.OpenAi -> openAiRuntime(config, http ?: llmHttpClient(config.requestTimeout))
        is LlmProviderConfig.Anthropic -> anthropicRuntime(config, http ?: llmHttpClient(config.requestTimeout))
        is LlmProviderConfig.OpenAiCompatible -> compatibleRuntime(config, http ?: llmHttpClient(config.requestTimeout))

        is LlmProviderConfig.Codex ->
            codexRuntime(
                config,
                auth = requireNotNull(codexAuth) { "a codex provider needs a CodexAuthStore" },
                http = http ?: llmHttpClient(config.requestTimeout, codexRequestPlugin(codexAuth, codexRoutingHint(config.model, config.serviceTier))),
            )
    }

// responses is the one endpoint where tools work alongside reasoning, and every turn here carries tools;
// a conversation gets a cache key of its own, and the gpt-5.6 generation its explicit breakpoints.
private fun openAiRuntime(config: LlmProviderConfig.OpenAi, http: HttpClient): LlmRuntime {
    val id = config.model.trim()
    val reasons = openAiReasons(id)

    require(config.reasoningEffort == null || reasons) {
        "${config.envPrefix}_REASONING_EFFORT does not apply to $id: a model that does not reason takes no effort"
    }

    val model =
        LlmModel(
            provider = LlmProvider.OPENAI,
            id = id,
            contextWindowTokens = config.contextWindowTokens ?: OPENAI_CONTEXT_WINDOW,
            maxOutputTokens = OPENAI_MAX_OUTPUT,
            takesEffort = reasons,
        )

    val options = RequestOptions(reasoningEffort = config.reasoningEffort, promptCacheKey = PROMPT_CACHE_KEY)

    return LlmRuntime(
        providerLabel = "OpenAI",
        client =
            RetryingLlmClient(
                OpenAiClient(
                    http = http,
                    baseUrl = OPENAI_API_BASE_URL + OPENAI_API_PATH,
                    apiKey = config.apiKey,
                    endpoint = OpenAiEndpoint.RESPONSES,
                    statelessReasoning = true,
                    explicitPromptCaching = true,
                ),
            ),
        model = model,
        chatOptions = options,
        compactionOptions = options.copy(promptCacheKey = COMPACTION_CACHE_KEY, cachePrompt = false),
    )
}

private fun anthropicRuntime(config: LlmProviderConfig.Anthropic, http: HttpClient): LlmRuntime {
    val id = config.model.trim()
    val takesEffort = config.takesEffort ?: anthropicTakesEffort(id)
    val effort = config.reasoningEffort

    require(effort == null || effort in ANTHROPIC_EFFORTS) {
        "${config.envPrefix}_REASONING_EFFORT=[${effort?.requestValue}] is not an effort Anthropic takes. Supported values: " +
                ANTHROPIC_EFFORTS.joinToString { it.requestValue }
    }
    require(effort == null || takesEffort) {
        "${config.envPrefix}_REASONING_EFFORT does not apply to $id: a Claude model from before adaptive thinking takes no effort"
    }

    val model =
        LlmModel(
            provider = LlmProvider.ANTHROPIC,
            id = id,
            contextWindowTokens = config.contextWindowTokens ?: ANTHROPIC_CONTEXT_WINDOW,
            maxOutputTokens = config.maxOutputTokens ?: ANTHROPIC_MAX_OUTPUT,
            takesEffort = takesEffort,
        )

    return LlmRuntime(
        providerLabel = "Anthropic",
        client = RetryingLlmClient(AnthropicClient(http, config.apiKey)),
        model = model,
        chatOptions = RequestOptions(reasoningEffort = effort),
    )
}

/**
 * Whether a Claude model thinks adaptively and takes an effort, when the vendor's list was not asked.
 *
 * Both arrived with Claude 4.6, the release that also stopped dating model ids: every model the API still
 * serves under a dated snapshot id (`claude-haiku-4-5-20251001`) refuses `adaptive` and `effort` with a
 * 400, and every undated one takes both (checked against `GET /v1/models` on 2026-10-08).
 */
internal fun anthropicTakesEffort(modelId: String): Boolean = !DATED_ANTHROPIC_MODEL_ID.containsMatchIn(modelId)

private val DATED_ANTHROPIC_MODEL_ID = Regex("""-\d{8}$""")

/**
 * Whether an OpenAI model reasons, and so takes an effort and hands back reasoning to replay.
 *
 * The o-series and everything from gpt-5 on do; the gpt-4 and gpt-3.5 families and the `chatgpt-`
 * aliases do not, and are sent neither an effort nor a request for the encrypted reasoning they never
 * write. The platform's model list says nothing about it, so the id is all there is to go on.
 */
internal fun openAiReasons(modelId: String): Boolean = !NON_REASONING_OPENAI_MODEL_ID.containsMatchIn(modelId)

private val NON_REASONING_OPENAI_MODEL_ID = Regex("""^(gpt-4|gpt-3\.5|chatgpt-)""", RegexOption.IGNORE_CASE)

// parallel tool calls stay off: third-party models garble the sibling calls of a batch, and the agent
// executes tool calls sequentially anyway. the cache key is an openai extension, so it travels only to
// openai itself and not to a server that may reject unknown fields; the same goes for `store` and the
// encrypted reasoning the responses api hands back on request.
private fun compatibleRuntime(config: LlmProviderConfig.OpenAiCompatible, http: HttpClient): LlmRuntime {
    val baseUrl = config.baseUrl.trim().trimEnd('/')
    val official = baseUrl.equals(OPENAI_API_BASE_URL, ignoreCase = true)

    val model =
        LlmModel(
            provider = LlmProvider.OPENAI,
            id = config.model.trim(),
            contextWindowTokens = config.contextWindowTokens ?: ContextWindowPolicy.DEFAULT_CONTEXT_WINDOW_TOKENS,
            seesImages = config.seesImages == true,
            // only the platform itself is known to refuse reasoning fields to a model that does not reason
            takesEffort = !official || openAiReasons(config.model.trim()),
        )

    val options =
        RequestOptions(
            reasoningEffort = config.reasoningEffort,
            parallelToolCalls = false,
            promptCacheKey = PROMPT_CACHE_KEY.takeIf { official },
        )

    return LlmRuntime(
        providerLabel = "OpenAI-compatible ($baseUrl, ${config.endpoint.name.lowercase()})",
        client =
            RetryingLlmClient(
                OpenAiClient(
                    http = http,
                    baseUrl = baseUrl + OPENAI_API_PATH,
                    apiKey = config.apiKey,
                    endpoint = config.endpoint,
                    statelessReasoning = official,
                    explicitPromptCaching = official,
                    echoesReasoningContent = !official,
                    label = "OpenAI-compatible",
                ),
            ),
        model = model,
        chatOptions = options,
        compactionOptions = options.copy(promptCacheKey = COMPACTION_CACHE_KEY.takeIf { official }, cachePrompt = false),
    )
}

// the codex backend speaks the responses api, streaming only, without an api key: the http client stamps a
// currently valid chatgpt token on every request. the catalog's context window and vision verdict arrive
// in the config, filled in by the preflight.
private fun codexRuntime(config: LlmProviderConfig.Codex, auth: CodexAuthStore, http: HttpClient): LlmRuntime {
    val model =
        LlmModel(
            provider = LlmProvider.OPENAI,
            id = config.model.trim(),
            contextWindowTokens = config.contextWindowTokens ?: ContextWindowPolicy.DEFAULT_CONTEXT_WINDOW_TOKENS,
            seesImages = config.supportsVision,
        )

    val options =
        RequestOptions(
            reasoningEffort = config.reasoningEffort,
            verbosity = config.verbosity,
            serviceTier = config.serviceTier?.requestValue,
            parallelToolCalls = false,
            promptCacheKey = PROMPT_CACHE_KEY,
        )

    return LlmRuntime(
        providerLabel = "ChatGPT subscription (Codex)",
        client =
            RetryingLlmClient(
                OpenAiClient(
                    http = http,
                    baseUrl = CODEX_BACKEND_BASE_URL,
                    apiKey = null,
                    endpoint = OpenAiEndpoint.RESPONSES,
                    streamed = true,
                    statelessReasoning = true,
                    label = "Codex",
                    onResponse = codexCallObserver(auth.limits),
                ),
            ),
        model = model,
        chatOptions = options,
        compactionOptions = options.copy(promptCacheKey = COMPACTION_CACHE_KEY, cachePrompt = false),
    )
}
