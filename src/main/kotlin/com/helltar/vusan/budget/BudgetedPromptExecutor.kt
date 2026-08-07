package com.helltar.vusan.budget

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.PromptExecutorOperation
import ai.koog.prompt.executor.model.ResolvedModel
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.structure.json.generator.BasicJsonSchemaGenerator
import ai.koog.prompt.structure.json.generator.StandardJsonSchemaGenerator
import kotlinx.coroutines.flow.Flow

/**
 * Counts every completed LLM call against [budget] and refuses to start one the budget has no room for.
 *
 * It wraps the executor rather than the agent so that one place covers every call the bot makes: agent turns,
 * history recaps, group-log digests, and vision when it rides the chat model. Koog resolves a model before
 * executing, so both the [LLModel] and the [ResolvedModel] overloads are counted — each one delegates
 * outward, never to its sibling here, so a call is never counted twice.
 */
internal class BudgetedPromptExecutor(
    private val delegate: PromptExecutor,
    private val budget: TokenBudget
) : PromptExecutor() {

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
        metered { delegate.execute(prompt, model, tools) }

    override suspend fun execute(prompt: Prompt, model: ResolvedModel, tools: List<ToolDescriptor>): Message.Assistant =
        metered { delegate.execute(prompt, model, tools) }

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): LLMChoice =
        meteredChoices { delegate.executeMultipleChoices(prompt, model, tools) }

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        resolvedModel: ResolvedModel,
        tools: List<ToolDescriptor>
    ): LLMChoice =
        meteredChoices { delegate.executeMultipleChoices(prompt, resolvedModel, tools) }

    // streaming responses are never counted: nothing in the bot streams, and a stream reports its usage only
    // once the flow is collected, long after the call it should have been charged to.
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        delegate.executeStreaming(prompt, model, tools)

    override fun executeStreaming(
        prompt: Prompt,
        resolvedModel: ResolvedModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> =
        delegate.executeStreaming(prompt, resolvedModel, tools)

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        delegate.moderate(prompt, model)

    override suspend fun moderate(prompt: Prompt, model: ResolvedModel): ModerationResult =
        delegate.moderate(prompt, model)

    override suspend fun resolveModel(model: LLModel, promptExecutorOperation: PromptExecutorOperation): ResolvedModel =
        delegate.resolveModel(model, promptExecutorOperation)

    override suspend fun models(): List<LLModel> = delegate.models()

    override fun getStandardJsonSchemaGenerator(model: LLModel): StandardJsonSchemaGenerator =
        delegate.getStandardJsonSchemaGenerator(model)

    override fun getBasicJsonSchemaGenerator(model: LLModel): BasicJsonSchemaGenerator =
        delegate.getBasicJsonSchemaGenerator(model)

    override fun close() = delegate.close()

    private suspend fun metered(call: suspend () -> Message.Assistant): Message.Assistant {
        val owner = checkedOwner()
        return call().also { budget.record(owner, it.metaInfo) }
    }

    private suspend fun meteredChoices(call: suspend () -> LLMChoice): LLMChoice {
        val owner = checkedOwner()
        return call().also { choices -> choices.forEach { budget.record(owner, it.metaInfo) } }
    }

    // whose share this call comes out of, once the budget agrees it may happen at all.
    private suspend fun checkedOwner(): Long? {
        val owner = currentBudgetOwner()
        budget.stopFor(owner)?.let { throw TokenBudgetExhaustedException(it) }
        return owner
    }
}

private suspend fun TokenBudget.record(userId: Long?, meta: ResponseMetaInfo) =
    record(userId, meta.inputTokensCount, meta.outputTokensCount)
