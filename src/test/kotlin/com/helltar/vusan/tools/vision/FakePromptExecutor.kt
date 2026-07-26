package com.helltar.vusan.tools.vision

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow

internal val TEST_MODEL = LLModel(provider = LLMProvider.OpenAI, id = "test", capabilities = emptyList())

// captures the prompt a vision client builds; both clients only ever call `execute`.
internal class FakePromptExecutor(private val response: String = "description") : PromptExecutor() {

    var callCount = 0
        private set

    var receivedPrompt: Prompt? = null
        private set

    val promptText: String
        get() = receivedPrompt?.messages.orEmpty().joinToString("\n") { it.textContent() }

    val attachmentCount: Int
        get() =
            receivedPrompt?.messages.orEmpty()
                .filterIsInstance<Message.User>()
                .sumOf { user -> user.parts.count { it is MessagePart.Attachment } }

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        callCount++
        receivedPrompt = prompt
        return Message.Assistant(content = response, metaInfo = ResponseMetaInfo.Empty)
    }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        error("executeStreaming not used in test")

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        error("moderate not used in test")

    override fun close() = Unit
}
