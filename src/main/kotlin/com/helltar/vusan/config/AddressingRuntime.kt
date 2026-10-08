package com.helltar.vusan.config

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.llm.ChatRequest
import com.helltar.vusan.llm.LlmClient
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.Message
import com.helltar.vusan.llm.ReasoningEffort
import com.helltar.vusan.llm.RequestOptions
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*

/**
 * Ambient addressing: answering a group message that calls the bot by name, or follows up on its answer,
 * without a mention, a reply or a command. `ADDRESSING_ENABLED` is the switch, and it needs
 * `ADDRESSING_MODEL`.
 *
 * [names] are the spellings the chat uses besides the bot's own display name; empty means the ones
 * derived from its profile.
 */
data class AddressingConfig(
    val provider: LlmProviderConfig,
    val names: List<String>,
) {

    init {
        require(names.none { it.isBlank() }) { "ADDRESSING_NAMES must not contain a blank name" }
    }
}

/** The small model that tells whether a group message nobody tagged the bot in is meant for it. */
data class AddressingRuntime(
    val client: LlmClient,
    val model: LlmModel,
    val options: RequestOptions,
)

/**
 * Always a model of its own, never the chat model: the chat provider is whatever the deployment runs,
 * and a verdict that has to arrive in about a second was measured on small OpenAI models only — a large
 * reasoning model would be slow here, and DeepSeek got one message in twelve wrong. A yes-or-no over a
 * few lines of chat needs no reasoning, and every token of it is latency, so unless the deployment asks
 * for an effort the model gets the least it takes. [http] is for tests, as in [resolveLlmRuntime].
 */
suspend fun resolveAddressingRuntime(config: AddressingConfig, codexAuth: CodexAuthStore? = null, http: HttpClient? = null): AddressingRuntime {
    val runtime = resolveLlmRuntime(config.provider, codexAuth, http)
    val options = runtime.chatOptions.copy(promptCacheKey = runtime.chatOptions.promptCacheKey?.let { ADDRESSING_CACHE_KEY })

    return AddressingRuntime(
        client = runtime.client,
        model = runtime.model,
        options = options.copy(reasoningEffort = config.provider.reasoningEffort ?: runtime.leastEffort(options)),
    )
}

// the least the model takes: the least its vendor listed at startup — the plan's catalog, anthropic's and
// deepseek's model lists all state them — and where nobody listed any, what the model itself says when
// asked once. a model that takes no effort at all is sent none.
private suspend fun LlmRuntime.leastEffort(options: RequestOptions): ReasoningEffort? =
    when {
        !model.takesEffort -> null
        model.efforts != null -> model.efforts.minOrNull()
        else -> probeLeastEffort(options)
    }

/**
 * The least of `none` and `low` the model takes, found by asking it once at startup.
 *
 * OpenAI's models disagree on the floor and no list states it: on 2026-10-08 gpt-5.5, every gpt-5.6,
 * gpt-6-luna and gpt-6-sol took `none`, while gpt-6.1-sol, gpt-6-astra and gpt-5-mini refused it with a
 * 400 — and every one of them took `low`. A check that could not be made at all settles on `low` too,
 * since a refused effort would fail every verdict while a needless one only slows them.
 */
private suspend fun LlmRuntime.probeLeastEffort(options: RequestOptions): ReasoningEffort {
    val probe = ChatRequest(listOf(Message.User("Reply with: ok")), options = options.copy(reasoningEffort = ReasoningEffort.NONE, maxOutputTokens = PROBE_OUTPUT_TOKENS))

    return try {
        client.complete(model, probe)
        ReasoningEffort.NONE
    } catch (e: Throwable) {
        e.rethrowIfCancellation()
        log.info { "addressing: ${model.id} does not take effort=[none] (${e.message?.lineSequence()?.firstOrNull()}); using low" }
        ReasoningEffort.LOW
    }
}

// the least the responses api takes
private const val PROBE_OUTPUT_TOKENS = 16

private const val ADDRESSING_CACHE_KEY = "vusan-addressing"

private val log = KotlinLogging.logger {}
