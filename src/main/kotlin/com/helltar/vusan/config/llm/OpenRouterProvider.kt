package com.helltar.vusan.config.llm

import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.ReasoningEffort
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.RetryingLlmClient
import com.helltar.vusan.llm.anthropic.ANTHROPIC_EFFORTS
import com.helltar.vusan.llm.anthropic.AnthropicClient
import com.helltar.vusan.llm.llmHttpClient
import com.helltar.vusan.llm.openai.ResponsesClient
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.request.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal const val OPENROUTER_API_BASE_URL = "https://openrouter.ai/api"

// the models listed for this key; a free model the account's privacy settings exclude is still listed, and
// gets a 404 on its first request
private const val OPENROUTER_MODELS_URL = "$OPENROUTER_API_BASE_URL/v1/models/user"
private const val OPENROUTER_MODELS_PAGE = "https://openrouter.ai/models"
private const val CLAUDE_PREFIX = "anthropic/"
private const val OPENAI_PREFIX = "openai/"
private const val XAI_PREFIX = "x-ai/"

private val log = KotlinLogging.logger("ModelPreflight")

/**
 * OpenRouter: one key for every vendor's models, over the two protocols the clients here already speak,
 * chosen by the model:
 *
 * - A Claude model (`anthropic/…`) goes to the Messages API through the Anthropic client as is: adaptive
 *   thinking with signed blocks replayed through the tool loop, the effort as `output_config.effort`, and two
 *   cache breakpoints, the system one kept an hour. On Chat Completions Claude loses its thinking after a
 *   tool call and the effort goes unread.
 * - Every other model goes to the Responses API: stateless, with encrypted reasoning replayed, and on GPT-5.6
 *   the developer block's breakpoint as every upstream takes it (no `implicit` mode, which OpenRouter
 *   refuses, and no twelve-hour lifetime, which Azure refuses). It streams, since OpenRouter answers 200 as
 *   soon as an upstream takes the request, and a later failure is an error event in the stream.
 *
 * Either way the cache key also goes as `session_id`, keeping the conversation on the upstream holding its
 * prefix; otherwise OpenRouter routes by a hash of the opening messages, which the history window moves.
 */
internal fun openRouterRuntime(config: LlmProviderConfig.OpenRouter, http: HttpClient?): LlmRuntime {
    val id = config.model.trim()
    val claude = id.startsWith(CLAUDE_PREFIX)
    val effort = config.reasoningEffort

    require(!claude || effort == null || effort in ANTHROPIC_EFFORTS) {
        "${config.envPrefix}_REASONING_EFFORT=[${effort?.requestValue}] is not an effort Claude takes. Supported values: " +
                ANTHROPIC_EFFORTS.joinToString { it.requestValue }
    }

    val model =
        LlmModel(
            id = id,
            contextWindowTokens = config.contextWindowTokens,
            maxOutputTokens = config.maxOutputTokens ?: ANTHROPIC_MAX_OUTPUT.takeIf { claude },
            seesImages = config.seesImages == true,
            efforts = config.efforts,
            // other vendors take `additional_tools` too, but openrouter folds the item into the tool list,
            // costing the cache what a widened list does (gemini, grok, deepseek on 2026-10-24), so they list
            // offered tools up front
            offersToolsMidConversation = if (claude) claudeOffersToolsMidConversation(id) else id.startsWith(OPENAI_PREFIX),
            // grok, served only by xai there, reads back only what a request repeats: measured 2026-10-27 on
            // grok-4.7, a rebuilt turn read xai's own 1152-token prefix, an appended one the whole last
            // request. the other vendors measured cache the longest prefix or have differing upstreams
            cachesOnlyRepeatedPrefix = id.startsWith(XAI_PREFIX),
        )

    val httpClient = http ?: llmHttpClient(config.requestTimeout)

    val client =
        if (claude) {
            AnthropicClient(httpClient, config.apiKey, baseUrl = OPENROUTER_API_BASE_URL, label = "OpenRouter", routesBySession = true)
        } else {
            ResponsesClient(
                http = httpClient,
                baseUrl = "$OPENROUTER_API_BASE_URL/v1",
                apiKey = config.apiKey,
                streamed = true,
                statelessReasoning = true,
                explicitPromptCaching = true,
                gatewayPromptCaching = true,
                routesBySession = true,
                label = "OpenRouter",
            )
        }

    val options = RequestOptions(reasoningEffort = effort, promptCacheKey = PROMPT_CACHE_KEY)

    return LlmRuntime(
        providerLabel = "OpenRouter",
        client = RetryingLlmClient(client),
        model = model,
        chatOptions = options,
        compactionOptions = options.copy(promptCacheKey = COMPACTION_CACHE_KEY, cachePrompt = false),
    )
}

/**
 * OpenRouter's list of the models this key may use states the window, output ceiling, vision, tool calling
 * and efforts (`none` where thinking can be turned off). A model it omits would be refused every turn, so it
 * stops startup, as does a model without tool calls or an unlisted effort. A routing variant (`:nitro`) the
 * list may lack is read as its model.
 */
internal suspend fun openRouterPreflight(http: HttpClient, config: LlmProviderConfig.OpenRouter): LlmProviderConfig.OpenRouter {
    val model = config.model.trim()
    val listed = listedModels(http, "OpenRouter", OPENROUTER_MODELS_URL) { bearerAuth(config.apiKey) } ?: return config
    val facts = listed.firstOrNull { it.id == model } ?: listed.firstOrNull { it.id == model.substringBefore(':') }

    checkNotNull(facts) {
        "${config.envPrefix}_MODEL=[$model] is not a model OpenRouter serves this key. Check the id at $OPENROUTER_MODELS_PAGE " +
                "and the account's privacy and provider settings"
    }

    val parameters = (facts["supported_parameters"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    check(parameters == null || "tools" in parameters) {
        "${config.envPrefix}_MODEL=[$model] calls no tools on OpenRouter, and every turn of Vusan's offers them"
    }

    val claude = model.startsWith(CLAUDE_PREFIX)

    // claude turns thinking off by an effort the messages api lacks, so `none` stays with openai's protocol
    val efforts =
        (((facts["reasoning"] as? JsonObject)?.get("supported_efforts")) as? JsonArray).orEmpty()
            .mapNotNull { level -> ReasoningEffort.entries.firstOrNull { it.requestValue == (level as? JsonPrimitive)?.contentOrNull } }
            .filter { !claude || it in ANTHROPIC_EFFORTS }
            .toSet()

    check(!claude || efforts.isNotEmpty()) {
        "${config.envPrefix}_MODEL=[$model] does not take an effort; Vusan needs a Claude model from 4.6 on"
    }

    config.reasoningEffort?.let { configured ->
        check(configured in efforts) {
            "${config.envPrefix}_REASONING_EFFORT=[${configured.requestValue}] is not an effort $model takes. " +
                    if (efforts.isEmpty()) "It takes none, so leave the variable unset" else "Supported values: ${efforts.sorted().joinToString { it.requestValue }}"
        }
    }

    val topProvider = facts["top_provider"] as? JsonObject
    val inputs = ((facts["architecture"] as? JsonObject)?.get("input_modalities") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    log.info { "OpenRouter: model=[$model] confirmed against the models this key may use" }

    return config.copy(
        contextWindowTokens =
            config.contextWindowTokens
                ?: (topProvider?.get("context_length") as? JsonPrimitive)?.longOrNull
                ?: (facts["context_length"] as? JsonPrimitive)?.longOrNull,
        maxOutputTokens = (topProvider?.get("max_completion_tokens") as? JsonPrimitive)?.intOrNull,
        seesImages = inputs?.let { "image" in it },
        efforts = efforts,
    )
}

private val JsonObject.id: String?
    get() = (this["id"] as? JsonPrimitive)?.contentOrNull
