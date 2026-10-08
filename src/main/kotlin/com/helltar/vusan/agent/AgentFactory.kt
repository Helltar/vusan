package com.helltar.vusan.agent

import com.helltar.vusan.agent.conversation.ChatRole
import com.helltar.vusan.agent.conversation.ChatTurn
import com.helltar.vusan.agent.conversation.PromptConversation
import com.helltar.vusan.agent.conversation.toolCallArgsForStorage
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.limitTo
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.llm.ChatRequest
import com.helltar.vusan.llm.LlmClient
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.Message
import com.helltar.vusan.llm.Part
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.StopReason
import com.helltar.vusan.llm.TokenUsage
import com.helltar.vusan.llm.ToolDefinition
import com.helltar.vusan.llm.ToolResult
import com.helltar.vusan.llm.llmJson
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.request.ConversationScope
import com.helltar.vusan.tools.ToolCatalog
import com.helltar.vusan.tools.ToolFailure
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class ToolEvent(
    val toolCallId: String,
    val toolName: String,
    val args: String,
    val output: String,
    val isError: Boolean,
)

data class AgentPromptPreparation(
    val toolCatalog: ToolCatalog,
    val systemPrompt: String,
    val tokenBudget: ContextTokenBudget,
)

/**
 * Builds the turns of this deployment: the system prompt, the budget the history has to fit, and an
 * [AgentTurn] over the model client with a conversation's history and tools.
 */
class AgentFactory(
    private val client: LlmClient,
    private val model: LlmModel,
    private val options: RequestOptions = RequestOptions(),
    private val personality: String? = null,
    // the bot's own Telegram handle: inbound sanitizing strips the mention before the prompt is
    // built, so without this the model never sees the name people call it by.
    private val botUsername: String? = null,
    private val botDisplayName: String? = null,
    // model calls one turn may make, the last of them reserved for landing a turn that runs long
    private val maxModelCalls: Int,
    private val contextWindowPolicy: ContextWindowPolicy = ContextWindowPolicy(model),
) {

    init {
        require(maxModelCalls >= MIN_MODEL_CALLS) { "a turn needs at least $MIN_MODEL_CALLS model calls" }
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
                    tools = toolCatalog.visibleDefinitions(),
                ),
        )
    }

    /** What this conversation's model calls are labelled with in the log, when the provider names sessions. */
    fun sessionLogLabel(scope: ConversationScope): String? = options.forConversation(scope.toString()).promptCacheKey

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
        onToolStarting: (activity: ToolActivity?) -> Unit = {},
        // a turn nobody called the bot into outright may end without a word; see `owesDelivery`.
        mayStaySilent: Boolean = false,
    ): AgentTurn {
        val history =
            buildList {
                add(Message.System(preparation.systemPrompt))
                // the recap is written from quoted events, so it can carry a delimiter out of them.
                conversation.summary?.let { add(Message.User(xmlBlock("conversation_recap", it.neutralizePromptBlocks()))) }
                addAll(conversation.turns.toMessages())
            }

        return AgentTurn(
            client = client,
            model = model,
            options = options.forConversation(scope.toString()),
            history = history,
            catalog = preparation.toolCatalog,
            outbox = outbox,
            toolBudget = toolBudget,
            maxModelCalls = maxModelCalls,
            scope = scope,
            mayStaySilent = mayStaySilent,
            toolEvents = toolEvents,
            tokenUsage = tokenUsage,
            onToolStarting = onToolStarting,
        )
    }

    companion object {
        // a request, a wrap-up and the nudge in between
        const val MIN_MODEL_CALLS = 3
    }
}

/**
 * One turn of the agent: the model is asked, its tool calls are run and answered, and so on until it
 * replies without one — or until the turn runs out of model calls and is landed instead.
 *
 * Guards against flaky models, in two places:
 * - a tool call that arrives with no arguments at all for a tool that takes them (flaky models emit
 *   empty-arg siblings when they try to call tools in parallel) is answered with a validation error
 *   instead of being executed, so the follow-up request stays well-formed and the model reissues it;
 * - a turn that ends having delivered nothing — no `sendMessage`, media or reaction, and empty assistant
 *   text (flaky providers return an empty completion after a batch of tool results) — gets one nudge to
 *   actually deliver before finishing, so a full turn of research does not collapse into silence.
 *
 * Landing: the last model call of the turn is reserved for a wrap-up request carrying no tools at all,
 * so the model cannot spend it on one more search, and its answer — written from what it gathered,
 * saying what it could not finish — is queued into the outbox like any other message, so it survives even
 * a turn that already reacted or sent something (trailing agent text is otherwise dropped as duplicate
 * chatter). Without that, a research turn would die with every search it paid for still unanswered.
 */
class AgentTurn internal constructor(
    private val client: LlmClient,
    private val model: LlmModel,
    private val options: RequestOptions,
    private val history: List<Message>,
    private val catalog: ToolCatalog,
    private val outbox: BotOutbox,
    private val toolBudget: TurnToolBudget,
    private val maxModelCalls: Int,
    private val scope: ConversationScope,
    private val mayStaySilent: Boolean,
    private val toolEvents: (ToolEvent) -> Unit,
    private val tokenUsage: (TokenUsage) -> Unit,
    private val onToolStarting: (activity: ToolActivity?) -> Unit,
) {

    private var calls = 0
    private var eventSeq = 0

    /** Runs the turn for [currentTurn] and returns the model's closing text, which may be empty. */
    suspend fun run(currentTurn: String): String {
        val messages = history.toMutableList()
        messages += Message.User(currentTurn)

        var nudged = false
        var lowBudgetWarned = false

        while (true) {
            val reply = request(messages, catalog.visibleDefinitions())
            messages += reply

            if (reply.toolCalls.isEmpty()) {
                // a turn nobody called the bot into may end in silence, but only on its first reply: a
                // model that sees the message was not for it says nothing before it calls anything.
                val silenceAllowed = mayStaySilent && calls == 1

                if (!owesDelivery(reply, nudged, outbox.hasQueuedOutput, silenceAllowed)) return reply.text

                nudged = true
                messages.dropTrailingEmptyAssistant()
                messages += Message.User(DELIVER_NUDGE)
                continue
            }

            // this batch still runs — it is already paid for, and it may carry the delivery call — but once
            // the budget is this thin the results go to the wrap-up instead of buying another tool round.
            messages += Message.ToolResults(reply.toolCalls.map { execute(it) })

            if (outOfModelCalls(calls, maxModelCalls)) return wrapUp(messages)

            // the model has to hear that the reserve is running out without being asked, or it spends the rest
            // of the turn on reads that come back cut in half. once per run, and after the results rather than
            // before them: nothing may come between an assistant's tool call and that call's result.
            if (!lowBudgetWarned && toolBudget.isLow) {
                lowBudgetWarned = true
                log.info { "tool result budget low for $scope: ${toolBudget.remainingTokens} of ${toolBudget.totalTokens} tokens left" }
                messages += Message.User(toolBudget.report())
            }
        }
    }

    private suspend fun request(messages: List<Message>, tools: List<ToolDefinition>): Message.Assistant {
        logPromptDump(messages, model.id, tools.map { it.name })
        calls++

        // a copy: the list grows as the turn goes on, and a client may keep the request it was handed
        val reply = client.complete(model, ChatRequest(messages.toList(), tools, options))

        reply.usage?.let(tokenUsage)

        // a safety classifier declined the request: the reply is empty and a retry of the same prompt is
        // declined again, so the log has to say why the turn ends without a word.
        if (reply.stopReason == StopReason.REFUSAL) log.warn { "model declined the request for $scope" }
        if (reply.stopReason == StopReason.MAX_TOKENS) log.warn { "model hit its output ceiling for $scope" }

        return reply.message
    }

    private suspend fun wrapUp(messages: MutableList<Message>): String {
        log.warn { "model calls spent for $scope (limit $maxModelCalls); answering with what the turn already gathered" }

        messages += Message.User(TOOL_BUDGET_WRAP_UP)
        val answer = request(messages, tools = emptyList()).text

        // queue it rather than leave it as the run's trailing text: a turn that already reacted or sent a
        // message has that text dropped as duplicate chatter, and here it is the whole answer.
        if (answer.isNotBlank() && !outbox.enqueueText(answer)) {
            log.warn { "no room left in the outbox for the wrap-up answer for $scope" }
        }

        return answer
    }

    private suspend fun execute(call: Part.ToolCall): ToolResult {
        val tool = catalog.find(call.name)
        val args = call.arguments.toString()

        onToolStarting(toolActivityFor(call.name))

        val (output, isError) =
            when {
                tool == null ->
                    "There is no tool named `${call.name}`. The tools you can call are: ${catalog.tools.joinToString { it.name }}." to true

                call.arguments.isEmpty() && tool.requiredParameters.isNotEmpty() ->
                    garbledCallMessage(tool.name, tool.requiredParameters) to true

                else -> runTool(tool.name) { tool.call(call.arguments) }
            }

        log.info { "tool call: name=[${call.name}] error=$isError args=[${args.collapseWhitespaceAndCap(TOOL_LOG_ARGS_MAX_CHARS).orEmpty()}]" }

        toolEvents(
            ToolEvent(
                toolCallId = call.id.ifBlank { "${call.name}-${eventSeq++}" },
                toolName = call.name,
                args = args,
                output = output,
                isError = isError,
            ),
        )

        // the model sees a result bounded to what is left of the run's reserve; history keeps it whole
        val bounded = output.boundedToolText(toolBudget.remainingTokens)
        toolBudget.spend(estimateTokens(bounded))

        return ToolResult(call.id, call.name, bounded, isError)
    }

    // every failure becomes the result the model reads: the guard's own message, a decoding complaint about
    // the arguments, or the bare fact for anything else — which also goes to the log with its trace, since
    // a tool that throws past its guard points at a bug.
    private suspend fun runTool(name: String, block: suspend () -> String): Pair<String, Boolean> =
        try {
            block() to false
        } catch (e: ToolFailure) {
            e.message.orEmpty() to true
        } catch (e: IllegalArgumentException) {
            "Tool `$name` rejected its arguments: ${e.message}" to true
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            log.error(t) { "tool $name failed outside its guard" }
            "Tool `$name` failed: ${t.message ?: t::class.simpleName}" to true
        }

    private companion object {
        const val TOOL_LOG_ARGS_MAX_CHARS = 300
        val log = KotlinLogging.logger {}
    }
}

/**
 * Stored turns as the messages a request carries. Tool calls of one batch share the assistant message
 * that made them, and their results share the one message that answers them, the way both APIs want it.
 */
internal fun List<ChatTurn>.toMessages(): List<Message> =
    buildList {
        val pendingResults = mutableListOf<ToolResult>()

        fun flushResults() {
            if (pendingResults.isNotEmpty()) add(Message.ToolResults(pendingResults.toList()))
            pendingResults.clear()
        }

        for (turn in this@toMessages) {
            when (turn.role) {
                ChatRole.USER -> {
                    flushResults()
                    add(Message.User(turn.content))
                }

                ChatRole.ASSISTANT -> {
                    flushResults()
                    add(Message.Assistant(turn.content))
                }

                ChatRole.TOOL_CALL -> {
                    val call =
                        Part.ToolCall(
                            id = checkNotNull(turn.toolCallId) { "TOOL_CALL row without toolCallId" },
                            name = checkNotNull(turn.toolName) { "TOOL_CALL row without toolName" },
                            arguments = storedToolArgs(turn.content),
                        )

                    val previous = lastOrNull() as? Message.Assistant

                    if (pendingResults.isEmpty() && previous != null && previous.parts.all { it is Part.ToolCall }) {
                        set(lastIndex, Message.Assistant(previous.parts + call))
                    } else {
                        flushResults()
                        add(Message.Assistant(listOf(call)))
                    }
                }

                ChatRole.TOOL_RESULT ->
                    pendingResults +=
                        ToolResult(
                            callId = checkNotNull(turn.toolCallId) { "TOOL_RESULT row without toolCallId" },
                            name = checkNotNull(turn.toolName) { "TOOL_RESULT row without toolName" },
                            output = turn.content,
                            isError = turn.toolIsError ?: false,
                        )
            }
        }

        flushResults()
    }

// the stored arguments are the json the model sent, bounded; whatever does not read as an object replays as none
private fun storedToolArgs(content: String): JsonObject =
    runCatching { llmJson.parseToJsonElement(toolCallArgsForStorage(content)).jsonObject }.getOrDefault(JsonObject(emptyMap()))

private const val TRUNCATION_NOTICE = "\n[tool result truncated for the model context]"
private const val OMITTED_NOTICE = "[tool result omitted: model context budget exhausted]"

/**
 * Caps a tool result to what is left of the run's reserve, in estimated tokens.
 *
 * How many characters a token budget buys depends on the text, because the estimator reads bytes: a
 * cyrillic character spends two where a latin one spends one. Measuring the text being cut keeps both
 * honest, and whatever the cut still overshoots the caller charges back at the real estimate.
 */
internal fun String.boundedToolText(maxTokens: Int): String {
    if (estimateTokens(this) <= maxTokens) return this

    val maxChars =
        (maxTokens.toLong() * ESTIMATED_BYTES_PER_TOKEN * length / encodeToByteArray().size).toInt()

    if (maxChars <= TRUNCATION_NOTICE.length) return OMITTED_NOTICE

    return limitTo(maxChars - TRUNCATION_NOTICE.length) + TRUNCATION_NOTICE
}

// the last call is the wrap-up's, so a batch of tool results after the one before it goes to the wrap-up
// rather than buying another round the turn could not answer.
private const val WRAP_UP_MODEL_CALLS = 1

internal fun outOfModelCalls(callsMade: Int, maxModelCalls: Int): Boolean = maxModelCalls - callsMade <= WRAP_UP_MODEL_CALLS

internal fun garbledCallMessage(tool: String, required: List<String>): String =
    "Tool `$tool` was called with no arguments at all; it takes: ${required.joinToString(", ")}. " +
            "Reissue it as a single, complete call with its arguments."

private const val TOOL_BUDGET_WRAP_UP =
    "This turn has used up its tool budget — no further tool calls will run, and this is your last reply. " +
            "Answer now from what you have already gathered; your text goes straight to the user. " +
            "Report what you did find, and state plainly which parts you could not finish. " +
            "Do not promise to continue later."

private const val DELIVER_NUDGE =
    "Your turn ended without sending anything to the user — no message, media, or reaction was delivered. " +
            "Deliver your answer now by calling `sendMessage` (or the appropriate media or reaction tool). " +
            "Do not reply with empty text."

// the model ended its turn without putting anything in front of the user: it delivered nothing (no tool
// call to execute, no caption text) and the outbox holds nothing to send. an announced plan does not count
// as delivering — that is the promise, not the answer. nudged at most once, to avoid looping on a
// stubbornly empty model.
internal fun owesDelivery(
    reply: Message.Assistant,
    nudged: Boolean,
    outboxHasOutput: Boolean,
    silenceAllowed: Boolean,
): Boolean =
    !silenceAllowed && !nudged && reply.deliveredNothing() && !outboxHasOutput

// true when the assistant ended its turn with nothing for the user: no tool call left to execute
// (so nothing more is coming this turn) and no plain text to fall back on as a caption.
internal fun Message.Assistant.deliveredNothing(): Boolean = toolCalls.isEmpty() && text.isBlank()

// an empty reply leaves an assistant message with no parts, which openai rejects on the next request as
// a message with neither content nor tool calls; drop it before re-requesting. a blank-text reply stays:
// it still serializes to a valid string content.
internal fun MutableList<Message>.dropTrailingEmptyAssistant() {
    while (lastOrNull().let { it is Message.Assistant && it.parts.isEmpty() }) removeAt(lastIndex)
}
