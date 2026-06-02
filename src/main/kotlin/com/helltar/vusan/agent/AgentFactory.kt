package com.helltar.vusan.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.*
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.environment.ToolResultKind
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import ai.koog.serialization.JSONObject
import com.helltar.vusan.agent.history.ChatRole
import com.helltar.vusan.agent.history.PromptHistory
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.request.RequestContext
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
    private val chatParams: LLMParams = LLMParams(),
    private val persona: String? = null,
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
                system(systemPromptFor(persona ?: DEFAULT_PERSONA))
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

                system(currentTimeSystemBlock())
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

        val nodeExecuteTool by node<ToolCalls, ReceivedToolResults>("executeValidToolCalls") { toolCalls ->
            ReceivedToolResults(
                toolCalls.toolCalls.map { call ->
                    val missing = call.missingRequiredArgs(llm.toolRegistry)

                    if (missing.isEmpty())
                        environment.executeTool(call)
                    else
                        garbledToolCallResult(call, missing)
                }
            )
        }

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

// Flaky OpenAI-compatible models garble parallel tool calls: sibling calls in the same batch arrive
// with empty `{}` args. Required args declared by the tool that the call omitted entirely.
private fun MessagePart.Tool.Call.missingRequiredArgs(registry: ToolRegistry): List<String> {
    val required = registry.getToolOrNull(tool)?.descriptor?.requiredParameters.orEmpty()
    if (required.isEmpty()) return emptyList()
    val provided = runCatching { argsJson.keys }.getOrDefault(emptySet())
    return required.map { it.name }.filterNot { it in provided }
}

// Synthesize a ValidationError result for a garbled call instead of handing it to the executor,
// which would throw a reflection exception that Koog logs as an ERROR with a full stack trace. This
// still satisfies the tool_call id (keeping the follow-up LLM request well-formed) and tells the
// model to reissue a complete call.
private fun garbledToolCallResult(call: MessagePart.Tool.Call, missing: List<String>): ReceivedToolResult {
    val names = missing.joinToString(", ")

    return ReceivedToolResult(
        id = call.id,
        tool = call.tool,
        toolArgs = JSONObject(emptyMap()),
        toolDescription = null,
        output = "Tool `${call.tool}` was called without required argument(s): $names. " +
                "Reissue it as a single, complete call with all required arguments.",
        resultKind = ToolResultKind.ValidationError(
            IllegalArgumentException("Missing required argument(s): $names")
        ),
        result = null
    )
}

private val LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
private val DAY_OF_WEEK = DateTimeFormatter.ofPattern("EEEE")

private fun currentTimeSystemBlock(): String {
    val timezone = ZoneId.systemDefault()
    val now = ZonedDateTime.now(timezone)
    return "Current time: ${LOCAL_DATE_TIME.format(now)} ${timezone.id} (${DAY_OF_WEEK.format(now)})"
}
