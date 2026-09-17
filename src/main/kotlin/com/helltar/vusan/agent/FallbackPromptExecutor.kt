package com.helltar.vusan.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.PromptExecutorOperation
import ai.koog.prompt.executor.model.ResolvedModel
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.structure.json.generator.BasicJsonSchemaGenerator
import ai.koog.prompt.structure.json.generator.StandardJsonSchemaGenerator
import com.helltar.vusan.common.rethrowIfCancellation
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.Instant
import kotlin.time.toJavaDuration

/**
 * The primary provider with a second one behind it for when the first is out: a spent subscription, a
 * sign-in that expired. A call the primary refuses that way is repeated on the fallback with the
 * fallback's own model and params, and the primary is left alone until the deadline its refusal named,
 * after which one call probes it again.
 *
 * It wraps the executor rather than the agent so that one place covers every call the bot makes — a
 * turn, a history recap, a group-log digest, vision on the chat model — and so the turn that ran into
 * the limit finishes on the fallback instead of ending in "come back later". Koog resolves a model
 * before executing, so both the [LLModel] and the [ResolvedModel] overloads route; the fallback is
 * always given its own model, whichever overload the primary was asked through.
 */
internal class FallbackPromptExecutor(
    private val primary: PromptExecutor,
    private val primaryLabel: String,
    private val fallback: PromptExecutor,
    private val fallbackLabel: String,
    private val fallbackModel: LLModel,
    private val fallbackParams: LLMParams,
    private val clock: Clock = Clock.systemUTC(),
) : PromptExecutor() {

    @Volatile
    private var primaryDownUntil: Instant? = null

    /** The model answering right now while the primary is out, or `null` when it is the primary's turn. */
    val fallbackModelInUse: String?
        get() = fallbackModel.id.takeIf { primaryDownUntil?.isAfter(clock.instant()) == true }

    private val onFallback: Boolean
        get() = fallbackModelInUse != null

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
        routed(prompt, { primary.execute(prompt, model, tools) }) { fallback.execute(it, fallbackModel, tools) }

    override suspend fun execute(prompt: Prompt, model: ResolvedModel, tools: List<ToolDescriptor>): Message.Assistant =
        routed(prompt, { primary.execute(prompt, model, tools) }) { fallback.execute(it, fallbackModel, tools) }

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): LLMChoice =
        routed(prompt, { primary.executeMultipleChoices(prompt, model, tools) }) {
            fallback.executeMultipleChoices(it, fallbackModel, tools)
        }

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        resolvedModel: ResolvedModel,
        tools: List<ToolDescriptor>,
    ): LLMChoice =
        routed(prompt, { primary.executeMultipleChoices(prompt, resolvedModel, tools) }) {
            fallback.executeMultipleChoices(it, fallbackModel, tools)
        }

    // nothing in the bot streams, and a stream reports its failure only once collected, so a stream is
    // routed by what is known at the time it is opened and never repeated.
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        if (onFallback) fallback.executeStreaming(prompt.forFallback(), fallbackModel, tools)
        else primary.executeStreaming(prompt, model, tools)

    override fun executeStreaming(
        prompt: Prompt,
        resolvedModel: ResolvedModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> =
        if (onFallback) fallback.executeStreaming(prompt.forFallback(), fallbackModel, tools)
        else primary.executeStreaming(prompt, resolvedModel, tools)

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        routed(prompt, { primary.moderate(prompt, model) }) { fallback.moderate(it, fallbackModel) }

    override suspend fun moderate(prompt: Prompt, model: ResolvedModel): ModerationResult =
        routed(prompt, { primary.moderate(prompt, model) }) { fallback.moderate(it, fallbackModel) }

    override suspend fun resolveModel(model: LLModel, promptExecutorOperation: PromptExecutorOperation): ResolvedModel =
        primary.resolveModel(model, promptExecutorOperation)

    override suspend fun models(): List<LLModel> = primary.models()

    override fun getStandardJsonSchemaGenerator(model: LLModel): StandardJsonSchemaGenerator =
        primary.getStandardJsonSchemaGenerator(model)

    override fun getBasicJsonSchemaGenerator(model: LLModel): BasicJsonSchemaGenerator =
        primary.getBasicJsonSchemaGenerator(model)

    override fun close() {
        primary.close()
        fallback.close()
    }

    private suspend fun <T> routed(prompt: Prompt, onPrimary: suspend () -> T, onFallback: suspend (Prompt) -> T): T {
        val now = clock.instant()
        val downUntil = primaryDownUntil

        if (downUntil != null && downUntil.isAfter(now)) return onFallback(prompt.forFallback())

        if (downUntil != null) log.info { "probing $primaryLabel again after its outage" }

        return try {
            onPrimary().also { if (downUntil != null) recovered() }
        } catch (e: Throwable) {
            e.rethrowIfCancellation()
            val outage = e.providerOutage(now) ?: throw e

            primaryDownUntil = now.plus(outage.toJavaDuration())

            log.warn {
                "$primaryLabel is out for ${outage.inWholeMinutes}m: ${e.providerErrorMessage()?.lineSequence()?.firstOrNull()}; " +
                        "answering from $fallbackLabel until then"
            }

            onFallback(prompt.forFallback())
        }
    }

    private fun recovered() {
        primaryDownUntil = null
        log.info { "$primaryLabel is back; $fallbackLabel stands down" }
    }

    // the fallback speaks with its own params, but the prompt cache key is the conversation's, not the
    // provider's: keeping it lets the fallback build a warm prefix per conversation the same way.
    private fun Prompt.forFallback(): Prompt = withParams(fallbackParams.withCacheKeyOf(params))

    private companion object {
        val log = KotlinLogging.logger {}
    }
}

private fun LLMParams.withCacheKeyOf(original: LLMParams): LLMParams {
    val key =
        when (original) {
            is OpenAIChatParams -> original.promptCacheKey
            is OpenAIResponsesParams -> original.promptCacheKey
            else -> null
        } ?: return this

    return when (this) {
        is OpenAIChatParams -> if (promptCacheKey == null) this else copy(promptCacheKey = key)
        is OpenAIResponsesParams -> if (promptCacheKey == null) this else copy(promptCacheKey = key)
        else -> this
    }
}
