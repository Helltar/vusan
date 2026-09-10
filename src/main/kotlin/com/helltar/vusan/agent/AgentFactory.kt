package com.helltar.vusan.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.agent.context.AIAgentGraphContextBase
import ai.koog.agents.core.agent.entity.AIAgentNodeBase
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
import ai.koog.serialization.kotlinx.toKotlinxJsonObject
import com.helltar.vusan.agent.conversation.ChatRole
import com.helltar.vusan.agent.conversation.PromptConversation
import com.helltar.vusan.agent.conversation.toolCallArgsForStorage
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.config.forConversation
import com.helltar.vusan.common.limitTo
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.request.ConversationScope
import com.helltar.vusan.tools.ToolCatalog
import io.github.oshai.kotlinlogging.KotlinLogging

// the strategy is built outside the class, so it cannot reach AgentFactory's own logger
private val strategyLog = KotlinLogging.logger("AgentStrategy")

data class ToolEvent(
    val toolCallId: String,
    val toolName: String,
    val args: String,
    val output: String,
    val isError: Boolean
)

data class TokenUsage(
    val inputTokens: Int?,
    val outputTokens: Int?,
    val totalTokens: Int?
)

data class AgentPromptPreparation(
    val toolCatalog: ToolCatalog,
    val systemPrompt: String,
    val tokenBudget: ContextTokenBudget
)

class AgentFactory(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val chatParams: LLMParams = LLMParams(),
    private val personality: String? = null,
    // the bot's own Telegram handle: inbound sanitizing strips the mention before the prompt is
    // built, so without this the model never sees the name people call it by.
    private val botUsername: String? = null,
    private val botDisplayName: String? = null,
    // Koog counts graph nodes here, not LLM calls: one tool round is an execute plus a send-results
    // node, so the ceiling on tool calls is roughly half of this. the last few are spent landing a
    // turn that runs long (see `outOfToolBudget`) instead of crashing it.
    private val maxIterations: Int,
    private val contextWindowPolicy: ContextWindowPolicy = ContextWindowPolicy(model)
) {

    private companion object {
        const val TOOL_LOG_ARGS_MAX_CHARS = 300
        val log = KotlinLogging.logger {}
    }

    // the catalog is built before the turn text, not here: what it defers goes into that text as
    // `<tool_groups>`, and the budget below has to weigh the finished prompt.
    fun prepare(toolCatalog: ToolCatalog, currentTurn: String): AgentPromptPreparation {
        val systemPrompt =
            systemPromptFor(personality ?: DEFAULT_PERSONALITY, model.id, botUsername, botDisplayName)

        return AgentPromptPreparation(
            toolCatalog = toolCatalog,
            systemPrompt = systemPrompt,
            tokenBudget =
                contextWindowPolicy.budget(
                    systemPrompt = systemPrompt,
                    currentTurn = currentTurn,
                    tools = toolCatalog.visibleDescriptors()
                )
        )
    }

    /** The reserve one run may spend on tool results, from which the runner opens its [TurnToolBudget]. */
    val liveToolResultMaxTokens: Int
        get() = contextWindowPolicy.liveToolResultMaxTokens

    fun build(
        scope: ConversationScope,
        conversation: PromptConversation,
        preparation: AgentPromptPreparation,
        outbox: BotOutbox,
        toolBudget: TurnToolBudget,
        toolEvents: (ToolEvent) -> Unit,
        tokenUsage: (TokenUsage) -> Unit,
        onToolStarting: (activity: ToolActivity?) -> Unit = {}
    ): AIAgent<String, String> {
        val seededPrompt =
            prompt(id = "vusan-turn-$scope", params = chatParams.forConversation(scope.toString())) {
                system(preparation.systemPrompt)
                // the recap is written from quoted events, so it can carry a delimiter out of them.
                conversation.summary?.let { user(xmlBlock("conversation_recap", it.neutralizePromptBlocks())) }

                conversation.turns.forEach { turn ->
                    when (turn.role) {
                        ChatRole.USER -> user(turn.content)
                        ChatRole.ASSISTANT -> assistant(turn.content)

                        ChatRole.TOOL_CALL ->
                            toolCall(
                                tool = checkNotNull(turn.toolName) { "TOOL_CALL row without toolName" },
                                args = toolCallArgsForStorage(turn.content),
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
            strategy =
                vusanSingleRunStrategy(outbox, preparation.toolCatalog, toolBudget, maxIterations, scope),
            toolRegistry = preparation.toolCatalog.registry,
            id = "vusan-turn-$scope"
        ) {
            install(EventHandler) {
                var seq = 0

                // koog dispatches a tool event here but emits no INFO line of its own, so log every call
                // ourselves (name + capped args) — uniform across all tools, not just those that self-log.
                fun record(toolCallId: String?, toolName: String, toolArgs: JSONObject, output: String, isError: Boolean) {
                    val args = toolArgs.toToolArgsJson()

                    log.info {
                        "tool call: name=[$toolName] error=$isError " +
                                "args=[${args.collapseWhitespaceAndCap(TOOL_LOG_ARGS_MAX_CHARS).orEmpty()}]"
                    }

                    toolEvents(
                        ToolEvent(
                            toolCallId = toolCallId ?: "$toolName-${seq++}",
                            toolName = toolName,
                            args = args,
                            output = output,
                            isError = isError
                        )
                    )
                }

                onLLMCallStarting { ctx ->
                    logPromptDump(ctx.prompt, ctx.model.id, ctx.tools)
                }

                onLLMCallCompleted { ctx ->
                    ctx.response?.metaInfo?.let { meta ->
                        tokenUsage(TokenUsage(meta.inputTokensCount, meta.outputTokensCount, meta.totalTokensCount))
                    }
                }

                onToolCallStarting { ctx ->
                    onToolStarting(toolActivityFor(ctx.toolName))
                }

                onToolCallCompleted { ctx ->
                    record(ctx.toolCallId, ctx.toolName, ctx.toolArgs, ctx.toolResult?.toString().orEmpty(), false)
                }

                onToolCallFailed { ctx ->
                    record(ctx.toolCallId, ctx.toolName, ctx.toolArgs, ctx.message, true)
                }

                onToolValidationFailed { ctx ->
                    record(ctx.toolCallId, ctx.toolName, ctx.toolArgs, ctx.message, true)
                }
            }
        }
    }
}

/**
 * Serializes tool-call arguments for the log and for history.
 *
 * Not `JSONObject.toString()`: koog's own implementation interpolates raw content between quotes and
 * escapes nothing, neither keys nor values, so one quote or backslash in an argument makes the whole
 * object unparseable — and `toolCallArgsForStorage`, which parses it back, then keeps `{}` and the
 * later turns of the run no longer know what the call was for.
 */
internal fun JSONObject.toToolArgsJson(): String = toKotlinxJsonObject().toString()

// mirrors Koog's built-in singleRunStrategy, but routes any assistant message without tool calls
// to nodeFinish — including empty responses. the default strategy uses `onTextMessage { true }`,
// which requires at least one non-empty `MessagePart.Text`; the model often emits an empty
// assistant with `finishReason=stop` after replying through the `sendMessage` tool, leaving no
// matching edge and triggering AIAgentStuckInTheNodeException.
//
// recovery: a model can also end its turn having delivered nothing at all — no `sendMessage`, no
// media, no reaction, and empty assistant text — which would leave the user with total silence
// despite a full turn of research. flaky OpenAI-compatible providers do this routinely, returning
// an empty completion after a batch of tool results. when that happens we nudge the model once to
// actually deliver, then let the normal edges finish. built per run so the strategy can read the
// live `outbox` to tell whether anything was delivered.
//
// landing: the last iterations of the run are reserved for a wrap-up request. koog otherwise throws
// AIAgentMaxNumberOfIterationsReachedException the moment the limit is passed, and a research turn
// dies with every search it paid for still unanswered.
private fun vusanSingleRunStrategy(
    outbox: BotOutbox,
    catalog: ToolCatalog,
    toolBudget: TurnToolBudget,
    maxIterations: Int,
    scope: ConversationScope
): AIAgentGraphStrategy<String, String> =
    strategy<String, String>("single_run") {
        var nudged = false
        var toolBudgetSpent = false
        var sentToolRevision = -1

        // koog seeds the run with every descriptor in the registry, and a deferred group's schemas would
        // ride along in every request from there on. narrowing is a session write, so it happens once per
        // widening rather than per request: before the first call, and after a batch that loaded a group.
        suspend fun AIAgentGraphContextBase.sendVisibleTools() {
            if (sentToolRevision == catalog.revision) return
            sentToolRevision = catalog.revision
            llm.writeSession { tools = catalog.visibleDescriptors() }
        }

        // the model ended its turn without putting anything in front of the user: it delivered
        // nothing (no tool call to execute, no caption text) and the outbox holds nothing to send. an
        // announced plan does not count as delivering — that is the promise, not the answer. nudge at
        // most once to avoid looping on a stubbornly empty model.
        fun undelivered(msg: Message.Assistant): Boolean =
            !nudged && msg.deliveredNothing() && !outbox.hasQueuedOutput

        val nodeNarrowTools by node<String, String>("narrowVisibleTools") { message ->
            sendVisibleTools()
            message
        }

        val nodeCallLLM by nodeLLMRequest()

        val nodeExecuteTool by node<ToolCalls, ReceivedToolResults>("executeValidToolCalls") { toolCalls ->
            // this batch still runs — it is already paid for, and it may carry the delivery call — but once
            // the budget is this thin the results go to the wrap-up instead of buying another tool round.
            toolBudgetSpent = stateManager.withStateLock { outOfToolBudget(it.iterations, maxIterations) }

            val results =
                toolCalls.toolCalls.map { call ->
                    val missing = call.missingRequiredArgs(llm.toolRegistry)

                    if (missing.isEmpty()) {
                        val result = environment.executeTool(call).boundedForLiveContext(toolBudget.remainingTokens)
                        toolBudget.spend(result.liveContextTokens)
                        result
                    } else {
                        garbledToolCallResult(call, missing)
                    }
                }

            // a `loadTools` call in this batch widened what the model may call; the next request carries it
            sendVisibleTools()

            ReceivedToolResults(results)
        }

        val nodeSendToolResult by nodeLLMSendToolResults()

        // the turn is out of iterations: answer from what it already gathered. the request carries no tools
        // at all, so the model cannot spend the reserve on one more search, and its text becomes the reply.
        val nodeWrapUp by node<ReceivedToolResults, String>("wrapUpWithoutTools") { results ->
            strategyLog.warn {
                "tool budget spent for $scope (limit $maxIterations iterations); " +
                        "answering with what the turn already gathered"
            }

            val answer =
                llm.writeSession {
                    appendPrompt {
                        user { results.toolResults.forEach { result -> toolResult(result.toMessagePart()) } }
                        user(TOOL_BUDGET_WRAP_UP)
                    }

                    requestLLMWithoutTools()
                }.textContent()

            // queue it rather than leave it as the run's trailing text: a turn that already reacted or sent a
            // message has that text dropped as duplicate chatter, and here it is the whole answer.
            if (answer.isNotBlank() && !outbox.enqueueText(answer)) {
                strategyLog.warn { "no room left in the outbox for the wrap-up answer for $scope" }
            }

            answer
        }

        val nodeNudgeDeliver by node<Message.Assistant, Message.Assistant>("nudgeDeliver") {
            nudged = true

            llm.writeSession {
                rewritePrompt { prompt -> prompt.withMessages { it.withoutTrailingEmptyAssistant() } }
                appendPrompt { user(DELIVER_NUDGE) }
                requestLLM()
            }
        }

        fun <I> finishWhenNoToolCalls(node: AIAgentNodeBase<I, Message.Assistant>) {
            edge(
                node forwardTo nodeFinish
                        onCondition { msg -> msg.parts.none { it is MessagePart.Tool.Call } }
                        transformed { msg -> msg.textContent() }
            )
        }

        edge(nodeStart forwardTo nodeNarrowTools)
        edge(nodeNarrowTools forwardTo nodeCallLLM)
        edge(nodeCallLLM forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeCallLLM forwardTo nodeNudgeDeliver onCondition { undelivered(it) })
        finishWhenNoToolCalls(nodeCallLLM)

        edge(nodeExecuteTool forwardTo nodeWrapUp onCondition { toolBudgetSpent })
        edge(nodeExecuteTool forwardTo nodeSendToolResult)
        edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeSendToolResult forwardTo nodeNudgeDeliver onCondition { undelivered(it) })
        finishWhenNoToolCalls(nodeSendToolResult)

        edge(nodeWrapUp forwardTo nodeFinish)

        edge(nodeNudgeDeliver forwardTo nodeExecuteTool onToolCalls { true })
        finishWhenNoToolCalls(nodeNudgeDeliver)
    }

private const val TRUNCATION_NOTICE = "\n[tool result truncated for the model context]"
private const val OMITTED_NOTICE = "[tool result omitted: model context budget exhausted]"

// what this result will cost the prompt. `parts` is what koog forwards to the LLM whenever it is set —
// and ToolBase.encodeResultToParts sets it, to a single Text part, for every tool that ran — so
// `output` is only the live text on the paths that never reached the tool at all.
internal val ReceivedToolResult.liveContextTokens: Int
    get() =
        parts?.sumOf { part -> (part as? MessagePart.Text)?.let { estimateTokens(it.text) } ?: 0 }
            ?: estimateTokens(output)

/**
 * Caps what this tool result contributes to the prompt, in estimated tokens.
 *
 * Both carriers are bounded: `ReceivedToolResult.toMessagePart` reads `parts ?: [Text(output)]`, so
 * bounding one of them alone leaves the other free to overrun the budget. Non-text parts are left
 * untouched — an image cannot be shortened, only dropped, and dropping it would silently answer a
 * different question than the tool was asked.
 */
internal fun ReceivedToolResult.boundedForLiveContext(maxTokens: Int): ReceivedToolResult {
    if (liveContextTokens <= maxTokens) return this

    val bounded = copy(output = output.boundedToolText(maxTokens))
    val parts = parts ?: return bounded
    var remaining = maxTokens

    return bounded.copy(
        parts =
            parts.map { part ->
                if (part !is MessagePart.Text) return@map part

                val text = part.text.boundedToolText(remaining)
                remaining = (remaining - estimateTokens(text)).coerceAtLeast(0)
                part.copy(text = text)
            }
    )
}

// how many characters a token budget buys depends on the text, because the estimator reads bytes: a
// cyrillic character spends two where a latin one spends one. Measuring the text being cut keeps both
// honest, and whatever the cut still overshoots the caller charges back at the real estimate.
private fun String.boundedToolText(maxTokens: Int): String {
    if (estimateTokens(this) <= maxTokens) return this

    val maxChars =
        (maxTokens.toLong() * ESTIMATED_BYTES_PER_TOKEN * length / encodeToByteArray().size).toInt()

    if (maxChars <= TRUNCATION_NOTICE.length) return OMITTED_NOTICE

    return limitTo(maxChars - TRUNCATION_NOTICE.length) + TRUNCATION_NOTICE
}

// koog counts one iteration per node execution, nodeStart and nodeFinish included, and throws the
// moment the count passes the limit. the wrap-up needs two of them (the no-tools request and
// nodeFinish); the spare covers the odd pass the nudge path adds.
private const val WRAP_UP_ITERATIONS = 4

internal fun outOfToolBudget(iterations: Int, maxIterations: Int): Boolean =
    maxIterations - iterations <= WRAP_UP_ITERATIONS

private const val TOOL_BUDGET_WRAP_UP =
    "This turn has used up its tool budget — no further tool calls will run, and this is your last reply. " +
            "Answer now from what you have already gathered; your text goes straight to the user. " +
            "Report what you did find, and state plainly which parts you could not finish. " +
            "Do not promise to continue later."

private const val DELIVER_NUDGE =
    "Your turn ended without sending anything to the user — no message, media, or reaction was delivered. " +
            "Deliver your answer now by calling `sendMessage` (or the appropriate media or reaction tool). " +
            "Do not reply with empty text."

// true when the assistant ended its turn with nothing for the user: no tool call left to execute
// (so nothing more is coming this turn) and no plain text to fall back on as a caption.
internal fun Message.Assistant.deliveredNothing(): Boolean =
    parts.none { it is MessagePart.Tool.Call } && textContent().isBlank()

// requestLLM appends the model reply to the session prompt, so an empty reply leaves an assistant
// message with no parts there. on the wire that becomes `{"role":"assistant"}` — no content, no
// tool_calls — which openai rejects with 400 on the next request; drop it before re-requesting.
// a blank-text reply stays: it still serializes to a valid string content.
internal fun List<Message>.withoutTrailingEmptyAssistant(): List<Message> =
    dropLastWhile { it is Message.Assistant && it.parts.isEmpty() }

// flaky OpenAI-compatible models garble parallel tool calls: sibling calls in the same batch arrive
// with empty `{}` args. Only that shape is caught, and only for a tool that takes arguments at all.
// Checking the declared parameters one at a time would reject far more than it should: koog's
// generated schema lists every parameter as required, Kotlin defaults included, so a call that simply
// left `isAnonymous` out was turned away even though koog decodes it into the default without
// complaint. A call that omits a genuinely required argument still fails in koog's own decoding,
// which answers the model with the parse error.
internal fun MessagePart.Tool.Call.missingRequiredArgs(registry: ToolRegistry): List<String> {
    val required = registry.getToolOrNull(tool)?.descriptor?.requiredParameters.orEmpty()
    if (required.isEmpty()) return emptyList()

    val provided = runCatching { argsJson.keys }.getOrDefault(emptySet())
    if (provided.isNotEmpty()) return emptyList()

    return required.map { it.name }
}

// synthesize a ValidationError result for a garbled call instead of handing it to the executor,
// which would throw a reflection exception that Koog logs as an ERROR with a full stack trace. this
// still satisfies the tool_call id (keeping the follow-up LLM request well-formed) and tells the
// model to reissue a complete call.
private fun garbledToolCallResult(call: MessagePart.Tool.Call, missing: List<String>): ReceivedToolResult {
    val names = missing.joinToString(", ")

    return ReceivedToolResult(
        id = call.id,
        tool = call.tool,
        toolArgs = JSONObject(emptyMap()),
        toolDescription = null,
        output = "Tool `${call.tool}` was called with no arguments at all; it takes: $names. " +
                "Reissue it as a single, complete call with its arguments.",
        resultKind = ToolResultKind.ValidationError(IllegalArgumentException("Missing required argument(s): $names")),
        result = null
    )
}

