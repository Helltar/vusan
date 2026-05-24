package com.helltar.vusan.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import com.helltar.vusan.agent.history.ChatRole
import com.helltar.vusan.agent.history.PromptHistory
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.RequestContext
import com.helltar.vusan.tools.ToolRegistryFactory
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class ToolEvent(
    val toolCallId: String,
    val toolName: String,
    val args: String,
    val output: String,
    val isError: Boolean
)

fun interface ToolEventSink {
    fun record(event: ToolEvent)
}

class AgentFactory(
    private val promptExecutor: PromptExecutor,
    private val toolRegistryFactory: ToolRegistryFactory,
    private val model: LLModel,
    private val botTimezone: ZoneId,
    private val chatParams: LLMParams = LLMParams(),
    private val systemPrompt: String = SYSTEM_PROMPT,
    private val maxIterations: Int = 60
) {

    fun build(
        userId: Long,
        history: PromptHistory,
        context: RequestContext,
        outbox: BotOutbox,
        toolEvents: ToolEventSink,
        messageContext: MessageContext? = null
    ): AIAgent<String, String> {
        val seededPrompt =
            prompt(id = "vusan-user-$userId", params = chatParams) {
                system(systemPrompt)
                system(currentTimeSystemBlock(botTimezone))
                messageContext?.toSystemPrompt()?.let(::system)

                history.summary?.let(::assistant)

                history.turns.forEach { turn ->
                    when (turn.role) {
                        ChatRole.USER -> user(turn.content)
                        ChatRole.ASSISTANT -> assistant(turn.content)
                        ChatRole.TOOL_CALL ->
                            toolCall(
                                tool = checkNotNull(turn.toolName) { "TOOL_CALL row without toolName" },
                                args = turn.content,
                                id = checkNotNull(turn.toolCallId) { "TOOL_CALL row without toolCallId" }
                            )
                        ChatRole.TOOL_RESULT ->
                            toolResult(
                                tool = checkNotNull(turn.toolName) { "TOOL_RESULT row without toolName" },
                                output = turn.content,
                                id = checkNotNull(turn.toolCallId) { "TOOL_RESULT row without toolCallId" },
                                isError = turn.toolIsError ?: false
                            )
                    }
                }
            }

        val agentConfig =
            AIAgentConfig(
                prompt = seededPrompt,
                model = model,
                maxAgentIterations = maxIterations
            )

        return AIAgent(
            promptExecutor = promptExecutor,
            agentConfig = agentConfig,
            strategy = vusanSingleRunStrategy,
            toolRegistry = toolRegistryFactory.buildRegistry(context, outbox),
            id = "vusan-user-$userId"
        ) {
            install(EventHandler) {
                var seq = 0

                onToolCallCompleted { ctx ->
                    toolEvents.record(
                        ToolEvent(
                            toolCallId = ctx.toolCallId ?: "${ctx.toolName}-${seq++}",
                            toolName = ctx.toolName,
                            args = ctx.toolArgs.toString(),
                            output = ctx.toolResult?.toString().orEmpty(),
                            isError = false
                        )
                    )
                }
                onToolCallFailed { ctx ->
                    toolEvents.record(
                        ToolEvent(
                            toolCallId = ctx.toolCallId ?: "${ctx.toolName}-${seq++}",
                            toolName = ctx.toolName,
                            args = ctx.toolArgs.toString(),
                            output = ctx.message,
                            isError = true
                        )
                    )
                }
                onToolValidationFailed { ctx ->
                    toolEvents.record(
                        ToolEvent(
                            toolCallId = ctx.toolCallId ?: "${ctx.toolName}-${seq++}",
                            toolName = ctx.toolName,
                            args = ctx.toolArgs.toString(),
                            output = ctx.message,
                            isError = true
                        )
                    )
                }
            }
        }
    }
}

// Mirrors Koog's built-in singleRunStrategy, but routes any assistant message without tool calls
// to nodeFinish — including empty responses. The default strategy uses `onTextMessage { true }`,
// which requires at least one non-empty `MessagePart.Text`; the model often emits an empty
// Assistant with `finishReason=stop` after replying through the `sendMessage` tool, leaving no
// matching edge and triggering AIAgentStuckInTheNodeException.
private val vusanSingleRunStrategy: AIAgentGraphStrategy<String, String> =
    strategy<String, String>("single_run") {
        val nodeCallLLM by nodeLLMRequest()
        val nodeExecuteTool by nodeExecuteTools()
        val nodeSendToolResult by nodeLLMSendToolResults()

        edge(nodeStart forwardTo nodeCallLLM)
        edge(nodeCallLLM forwardTo nodeExecuteTool onToolCalls { true })
        edge(
            nodeCallLLM forwardTo nodeFinish
                onCondition { msg -> msg.parts.none { it is MessagePart.Tool.Call } }
                transformed { msg -> msg.assistantTextOrEmpty() }
        )
        edge(nodeExecuteTool forwardTo nodeSendToolResult)
        edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCalls { true })
        edge(
            nodeSendToolResult forwardTo nodeFinish
                onCondition { msg -> msg.parts.none { it is MessagePart.Tool.Call } }
                transformed { msg -> msg.assistantTextOrEmpty() }
        )
    }

private fun Message.Assistant.assistantTextOrEmpty(): String =
    parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }

private val LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
private val DAY_OF_WEEK = DateTimeFormatter.ofPattern("EEEE")

private fun currentTimeSystemBlock(timezone: ZoneId): String {
    val now = ZonedDateTime.now(timezone)
    return "Current time: ${LOCAL_DATE_TIME.format(now)} ${timezone.id} (${DAY_OF_WEEK.format(now)})"
}
