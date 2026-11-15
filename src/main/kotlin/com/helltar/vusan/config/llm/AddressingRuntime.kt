package com.helltar.vusan.config.llm

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.llm.ChatRequest
import com.helltar.vusan.llm.LlmClient
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.Message
import com.helltar.vusan.llm.ReasoningEffort
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.codex.CodexAuthStore
import com.helltar.vusan.llm.typesafe.NoulQuestion
import com.helltar.vusan.llm.typesafe.SystemOneClient
import io.github.oshai.kotlinlogging.KotlinLogging
import com.helltar.vusan.llm.LlmException
import io.ktor.client.*
import io.ktor.http.*
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration.Companion.seconds

/**
 * Ambient addressing: answering a group message that names the bot or follows up on its answer without a
 * mention, reply or command. `ADDRESSING_ENABLED` is the switch and needs `ADDRESSING_MODEL`.
 *
 * [names] are the chat's spellings besides the bot's display name; empty means derived from its profile.
 * [judge] waits at most [VERDICT_TIMEOUT] per call whatever its role's settings say: a late verdict is
 * worthless.
 */
data class AddressingConfig(
    val judge: AddressingJudge,
    val names: List<String>,
) {

    init {
        require(names.none { it.isBlank() }) { "ADDRESSING_NAMES must not contain a blank name" }
    }

    companion object {
        /**
         * How long a verdict may take before the message counts as not for the bot. The model answered in
         * about a second, with a tail near three; a later verdict lands after the conversation moved on.
         */
        val VERDICT_TIMEOUT = 5.seconds
    }
}

/** What gives the verdict: a chat model asked in words, or a model that answers with a probability. */
sealed interface AddressingJudge {

    val model: String

    data class Llm(val provider: LlmProviderConfig) : AddressingJudge {
        override val model: String
            get() = provider.model
    }

    /** TypeSafe's Jev, reached with a key of its own: no chat provider serves it. */
    data class Jev(val apiKey: String, override val model: String) : AddressingJudge {

        init {
            require(apiKey.isNotBlank()) { "the api key must not be blank" }
            require(model.isNotBlank()) { "the model must not be blank" }
        }
    }
}

/** The small model judging whether an untagged group message is meant for the bot. */
data class AddressingRuntime(
    val client: LlmClient,
    val model: LlmModel,
    val options: RequestOptions,
)

/**
 * Always its own model, never the chat model: the verdict must arrive in about a second and was measured on
 * small OpenAI models only; a large reasoning model would be slow, and DeepSeek got one message in twelve
 * wrong. A yes-or-no over a few lines needs no reasoning and every token is latency, so the model gets its
 * least effort unless one is configured. [http] is for tests, as in [resolveLlmRuntime].
 */
suspend fun resolveAddressingRuntime(provider: LlmProviderConfig, codexAuth: CodexAuthStore? = null, http: HttpClient? = null): AddressingRuntime {
    val runtime = resolveLlmRuntime(provider, codexAuth, http)
    val options = runtime.chatOptions.copy(promptCacheKey = runtime.chatOptions.promptCacheKey?.let { ADDRESSING_CACHE_KEY })

    return AddressingRuntime(
        client = runtime.client,
        model = runtime.model,
        options = options.copy(reasoningEffort = provider.reasoningEffort ?: runtime.leastEffort(options)),
    )
}

/**
 * Asks Jev one question at startup, so a wrong key or model id stops the bot with TypeSafe's answer instead
 * of failing every verdict in silence. An unreachable or failing server only warns.
 */
suspend fun SystemOneClient.verified(judge: AddressingJudge.Jev): SystemOneClient {
    val probe = NoulQuestion("Is `state` a greeting?", whenTrue = "It greets someone.", whenFalse = "It says something else.")

    try {
        nouls(judge.model, JsonPrimitive("hello"), mapOf("greeting" to probe))
        log.info { "TypeSafe: model=[${judge.model}] answered the startup question" }
    } catch (e: LlmException) {
        val refused = e.status?.let { it in CLIENT_ERRORS && it != HttpStatusCode.TooManyRequests.value } == true

        check(!refused) {
            "TypeSafe refused ADDRESSING_MODEL=[${judge.model}] with ADDRESSING_API_KEY: ${e.message}"
        }

        log.warn { "TypeSafe: could not check model=[${judge.model}] (${e.message}); assuming it answers" }
    }

    return this
}

// `none` where the model takes it, which only asking tells; otherwise the least effort its vendor listed, or
// low with no list. a list with nothing to choose from leaves the effort out.
private suspend fun LlmRuntime.leastEffort(options: RequestOptions): ReasoningEffort? {
    val refusal = refusalOfNone(options) ?: return ReasoningEffort.NONE
    val least = if (model.efforts != null) model.efforts.minOrNull() else ReasoningEffort.LOW

    log.info { "addressing: ${model.id} does not take effort=[none] ($refusal); using ${least?.requestValue ?: "its default"}" }

    return least
}

/**
 * Why the model refuses `none`, or `null` when it takes it, found by asking once at startup.
 *
 * Nothing lists it and models disagree: on 2026-10-08 gpt-5.5, every gpt-5.6, gpt-6-luna and gpt-6-sol took
 * `none`, while gpt-6.1-sol, gpt-6-astra and gpt-5-mini gave a 400. The Codex backend takes it on luna and
 * terra though its catalog lists `low` as lowest, DeepSeek turns thinking off with it though it lists only
 * `low`, `high` and `max`, and Anthropic has no such effort at all, refused by its client before any request.
 * A failed check counts as a refusal: a refused effort would fail every verdict, a needless one only slows
 * them.
 */
private suspend fun LlmRuntime.refusalOfNone(options: RequestOptions): String? {
    val probe = ChatRequest(listOf(Message.User("Reply with: ok")), options = options.copy(reasoningEffort = ReasoningEffort.NONE, maxOutputTokens = PROBE_OUTPUT_TOKENS))

    return try {
        client.complete(model, probe)
        null
    } catch (e: Throwable) {
        e.rethrowIfCancellation()
        e.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()
    }
}

private val CLIENT_ERRORS = 400..499

// the least the responses api takes
private const val PROBE_OUTPUT_TOKENS = 16

private const val ADDRESSING_CACHE_KEY = "vusan-addressing"

private val log = KotlinLogging.logger {}
