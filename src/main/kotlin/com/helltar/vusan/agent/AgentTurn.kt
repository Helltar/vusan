package com.helltar.vusan.agent

import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.limitTo
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.llm.ChatRequest
import com.helltar.vusan.llm.LlmClient
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.Message
import com.helltar.vusan.llm.Part
import com.helltar.vusan.llm.Reply
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.StopReason
import com.helltar.vusan.llm.TokenUsage
import com.helltar.vusan.llm.ToolDefinition
import com.helltar.vusan.llm.ToolResult
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.request.ConversationScope
import com.helltar.vusan.tools.ToolCatalog
import com.helltar.vusan.tools.ToolFailure
import com.helltar.vusan.tools.message.MessageTools
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

data class ToolEvent(
    val toolCallId: String,
    val toolName: String,
    val args: String,
    val output: String,
    val isError: Boolean,
)

/**
 * The model, or a safety classifier in front of it, declined the request; [reason] is the provider's, if
 * given. The same prompt would be declined again.
 */
class ModelRefusal(val reason: String?) : RuntimeException("the model declined the request" + reason?.let { ": $it" }.orEmpty())

/**
 * One turn of the agent: ask the model, run its tool calls, answer them, and repeat until it replies without
 * one or runs out of model calls and is landed.
 *
 * Adjacent read-only calls in a batch run side by side; anything that writes keeps its place. Results are
 * recorded in batch order either way.
 *
 * The guards against flaky models are listed in `docs/architecture/turn.md` under Act: empty-argument calls
 * answered with a validation error, a reply cut by the output ceiling runs none of its calls, a turn that
 * delivered nothing gets one nudge (deliberate silence goes through `staySilent`), and an announced plan left
 * undone is sent back once.
 *
 * When the turn's own tool results outgrow the window, the oldest are folded away (`foldedToFit`) so long
 * work does not die on the context limit.
 *
 * Landing: the last model call is a wrap-up in which no tool may be called. Its answer, written from what was
 * gathered, is queued into the outbox like any message, so it survives a turn that already sent something,
 * where trailing agent text is dropped as duplicate chatter.
 */
class AgentTurn internal constructor(
    private val client: LlmClient,
    private val model: LlmModel,
    private val options: RequestOptions,
    private val history: List<Message>,
    // counts of [history] after which the next turn repeats this one's request, for the provider's cache
    private val stablePrefixes: List<Int> = emptyList(),
    private val catalog: ToolCatalog,
    private val outbox: BotOutbox,
    private val toolBudget: TurnToolBudget,
    private val maxModelCalls: Int,
    // the size the whole request may reach, in the budget's estimate, before the turn's results are folded
    private val promptTokenCeiling: Int,
    private val scope: ConversationScope,
    private val mayStaySilent: Boolean,
    private val toolEvents: (ToolEvent) -> Unit,
    private val tokenUsage: (TokenUsage) -> Unit,
    private val onToolStarting: (activity: ToolActivity?) -> Unit,
    // what the turn's calls produced, under the labels later calls use
    private val shelf: TurnShelf,
) {

    private var calls = 0
    private var eventSeq = 0

    // runs per (tool, arguments) pair, for the note under a repeated call
    private val callCounts = HashMap<String, Int>()

    // whether the model announced what it would do, and whether it then did anything
    private var announced = false
    private var workedSinceAnnouncement = false

    /**
     * The conversation as the last request held it, plus its reply, once [run] returns: what the next turn
     * sends ahead of its own message when it continues this one. Empty before that and after a run that
     * threw.
     */
    var sent: List<Message> = emptyList()
        private set

    /** Runs the turn for [currentTurn] and returns the model's closing text, which may be empty. */
    suspend fun run(currentTurn: String): String {
        val messages = history.toMutableList()
        messages += Message.User(currentTurn)

        return converse(messages).also {
            // drop an empty reply, as before a nudge: no endpoint accepts it back
            messages.dropTrailingSilentAssistant()
            sent = messages.toList()
        }
    }

    private suspend fun converse(messages: MutableList<Message>): String {
        val turnStart = messages.size - 1

        var nudged = false
        var lowBudgetWarned = false
        // the same list on every step: a group loaded mid-turn is offered in the conversation instead
        val tools = catalog.requestDefinitions()

        while (true) {
            offerLoadedTools(messages)
            foldToFit(messages, turnStart)

            val reply = request(messages, tools)
            val message = reply.message
            messages += message

            if (message.toolCalls.isEmpty()) {
                // an unaddressed turn may stay silent, but only on its first reply, before calling anything.
                val silenceAllowed = mayStaySilent && calls == 1
                val promised = announced && !workedSinceAnnouncement

                if (!promised && !owesDelivery(message, nudged, outbox.hasAnswered, silenceAllowed)) return message.text
                if (nudged) return message.text

                messages.dropTrailingSilentAssistant()

                // the nudge is a model call too, and the last one belongs to the wrap-up
                if (outOfModelCalls(calls, maxModelCalls)) return wrapUp(messages, turnStart)

                nudged = true

                if (promised) log.info { "model announced a plan and stopped for $scope; sending it back to the work" }

                messages += Message.User(if (promised) PROMISE_NUDGE else DELIVER_NUDGE)
                continue
            }

            // this batch still runs (it is paid for and may carry the delivery), but with the budget this
            // thin its results go to the wrap-up instead of another round. a batch cut by the output ceiling
            // does not run: its last call may be truncated yet look whole, so the model is told why and
            // reissues.
            val results =
                if (reply.stopReason == StopReason.MAX_TOKENS) {
                    message.toolCalls.map(::cutOffResult)
                } else {
                    executeBatch(message.toolCalls)
                }

            messages += Message.ToolResults(results)

            // the turn chose silence: asking again would only invite a closing line into a reply it decided
            // not to give
            if (outbox.silence != null) return ""

            if (outOfModelCalls(calls, maxModelCalls)) return wrapUp(messages, turnStart)

            // the model must hear the reserve is running low, or it spends the rest on reads that come back
            // cut. once per run, after the results: nothing may come between a tool call and its result.
            if (!lowBudgetWarned && toolBudget.isLow) {
                lowBudgetWarned = true
                log.info { "tool result budget low for $scope: ${toolBudget.remainingTokens} of ${toolBudget.totalTokens} tokens left" }
                messages += Message.User(toolBudget.report())
            }
        }
    }

    private suspend fun request(messages: List<Message>, tools: List<ToolDefinition>, mayCallTools: Boolean = true): Reply {
        logPromptDump(messages, model.id, tools.filterNot { it.deferred }.map { it.name })
        calls++

        // a copy: the list keeps growing, and a client may keep the request it was handed
        val reply = client.complete(model, ChatRequest(messages.toList(), tools, options, mayCallTools, stablePrefixes))

        reply.usage?.let(tokenUsage)

        // a declined reply is not an answer (a classifier may have stopped it mid-call) and would be declined
        // again, so the turn ends and the runner says why.
        if (reply.stopReason == StopReason.REFUSAL) throw ModelRefusal(reply.refusal)

        if (reply.stopReason == StopReason.MAX_TOKENS) {
            log.warn { "model hit its output ceiling for $scope; ${reply.message.toolCalls.size} tool call(s) in the reply will not run" }
        }

        return reply
    }

    // tools of groups loaded since, not yet offered, appended at the end: after whatever the turn added,
    // before the request that may call them
    private fun offerLoadedTools(messages: MutableList<Message>) {
        val offered = messages.filterIsInstance<Message.ToolsOffered>().flatMapTo(mutableSetOf()) { it.names }
        val fresh = catalog.loadedToolNames().filterNot { it in offered }

        if (fresh.isNotEmpty()) messages += Message.ToolsOffered(fresh)
    }

    // fold when the whole request would not fit: oldest results first, with the long arguments they answered,
    // never the latest batch. the history before the turn was planned to fit and stays. the tools are weighed
    // as the model sees them, wherever an endpoint puts them.
    private fun foldToFit(messages: MutableList<Message>, turnStart: Int) {
        val folded = messages.foldedToFit(promptTokenCeiling - estimateTokens(catalog.visibleDefinitions()), turnStart) ?: return
        val foldedResults = folded.count { it is Message.ToolResults && it.isFolded() } - messages.count { it is Message.ToolResults && it.isFolded() }

        log.warn { "turn history folded for $scope: ${messages.size} messages over ~$promptTokenCeiling tokens, $foldedResults result batch(es) dropped" }

        messages.clear()
        messages += folded
    }

    private suspend fun wrapUp(messages: MutableList<Message>, turnStart: Int): String {
        log.warn { "model calls spent for $scope (limit $maxModelCalls); answering with what the turn already gathered" }

        messages += Message.User(TOOL_BUDGET_WRAP_UP)

        // the pile is largest here, and the whole turn's work rides on this request
        offerLoadedTools(messages)
        foldToFit(messages, turnStart)

        // the tools stay defined, since the turn's calls are replayed with them, but none may be called
        val reply = request(messages, catalog.requestDefinitions(), mayCallTools = false).message
        messages += reply
        val answer = reply.text

        // queued rather than left as trailing text, which is dropped as duplicate chatter after a reaction or
        // message; here it is the whole answer.
        if (answer.isNotBlank() && !outbox.enqueueText(answer)) {
            log.warn { "no room left in the outbox for the wrap-up answer for $scope" }
        }

        return answer
    }

    // adjacent read-only calls run side by side, the rest in order; results are recorded in batch order, so
    // the budget and events read as sequential. every call takes its label before any run starts, so the
    // numbers follow the batch.
    private suspend fun executeBatch(batch: List<Part.ToolCall>): List<ToolResult> {
        val outcomes = mutableListOf<Triple<Part.ToolCall, TurnShelf.Call, Outcome>>()

        for (run in batch.runs()) {
            val opened = run.map { call -> call to shelf.open() }

            val performed =
                if (opened.size > 1) {
                    coroutineScope { opened.map { (call, slot) -> async { perform(call, slot) } }.awaitAll() }
                } else {
                    opened.map { (call, slot) -> perform(call, slot) }
                }

            outcomes += opened.zip(performed) { (call, slot), outcome -> Triple(call, slot, outcome) }
        }

        return outcomes.map { (call, slot, outcome) -> record(call, slot, outcome) }
    }

    private fun List<Part.ToolCall>.runs(): List<List<Part.ToolCall>> {
        val runs = mutableListOf<MutableList<Part.ToolCall>>()
        var lastRunReadOnly = false

        for (call in this) {
            val readOnly = catalog.find(call.name)?.readOnly == true

            if (readOnly && lastRunReadOnly) runs.last() += call else runs += mutableListOf(call)

            lastRunReadOnly = readOnly
        }

        return runs
    }

    // the tool runs inside its shelf slot, which its references resolve against and where its files land. the
    // slot is closed whatever happens, since a later call of the same run may wait on it.
    private suspend fun perform(call: Part.ToolCall, slot: TurnShelf.Call): Outcome {
        val tool = catalog.find(call.name)

        onToolStarting(toolActivityFor(call.name))

        val (output, isError) =
            when {
                tool == null ->
                    "There is no tool named `${call.name}`. The tools you can call are: ${catalog.tools.joinToString { it.name }}." to true

                call.arguments.isEmpty() && tool.requiredParameters.isNotEmpty() ->
                    garbledCallMessage(tool.name, tool.requiredParameters) to true

                else -> runTool(tool.name) { withContext(slot) { tool.call(call.arguments) } }
            }

        slot.close(output, call.name, copiesText = tool?.copiedToSandbox == true && !isError)

        log.info { "tool call: name=[${call.name}] #${slot.number} error=$isError args=[${call.arguments.toString().collapseWhitespaceAndCap(TOOL_LOG_ARGS_MAX_CHARS).orEmpty()}]" }

        return Outcome(output, isError)
    }

    private fun record(call: Part.ToolCall, slot: TurnShelf.Call, outcome: Outcome): ToolResult {
        if (call.name == MessageTools::announcePlan.name) announced = true else workedSinceAnnouncement = announced

        toolEvents(
            ToolEvent(
                toolCallId = call.id.ifBlank { "${call.name}-${eventSeq++}" },
                toolName = call.name,
                args = call.arguments.toString(),
                output = outcome.output,
                isError = outcome.isError,
            ),
        )

        // the model sees the result bounded to the remaining reserve, under its label; history keeps it whole
        // and unlabeled, since the label means nothing to the next turn
        val bounded = outcome.output.boundedToolText(toolBudget.remainingTokens)
        val labeled = slot.labeled(bounded, cut = bounded != outcome.output)
        val result = repeatedCallNote(call)?.let { "$labeled\n\n$it" } ?: labeled
        toolBudget.spend(estimateTokens(result))

        return ToolResult(call.id, call.name, result, outcome.isError)
    }

    // a stuck model repeats the same call and reads the same answer until the budget runs out; from the third
    // time on the result says so. a note, not a refusal, since a command may be polled on purpose. keys are
    // sorted, so argument order cannot hide a repeat
    private fun repeatedCallNote(call: Part.ToolCall): String? {
        val key = call.name + call.arguments.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }
        val times = (callCounts[key] ?: 0) + 1
        callCounts[key] = times

        return repeatedCallMessage(call.name, times).takeIf { times >= REPEATED_CALL_NOTE_FROM }
    }

    // every failure becomes the result the model reads: the guard's message, a decoding complaint, or the
    // bare fact for anything else, which is also logged with its trace since it points at a bug.
    private suspend fun runTool(name: String, block: suspend () -> String): Pair<String, Boolean> =
        try {
            block() to false
        } catch (e: TimeoutCancellationException) {
            // the tool's own deadline, not the turn's cancellation, which `rethrowIfCancellation` would take
            // it for
            "Tool `$name` timed out: ${e.message}" to true
        } catch (e: ToolFailure) {
            e.message.orEmpty() to true
        } catch (e: IllegalArgumentException) {
            "Tool `$name` rejected its arguments: ${e.message}" to true
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            log.error(t) { "tool $name failed outside its guard" }
            "Tool `$name` failed: ${t.message ?: t::class.simpleName}" to true
        }

    private class Outcome(val output: String, val isError: Boolean)

    private companion object {
        const val TOOL_LOG_ARGS_MAX_CHARS = 300
        const val REPEATED_CALL_NOTE_FROM = 3
        val log = KotlinLogging.logger {}
    }
}

private const val TRUNCATION_NOTICE = "\n[tool result truncated for the model context]"
private const val OMITTED_NOTICE = "[tool result omitted: model context budget exhausted]"

/**
 * Caps a tool result to the remaining reserve, in estimated tokens.
 *
 * The estimator reads bytes, so a cyrillic character costs two where a latin one costs one; measuring the
 * text being cut keeps both honest, and the caller charges back any overshoot at the real estimate.
 */
internal fun String.boundedToolText(maxTokens: Int): String {
    if (estimateTokens(this) <= maxTokens) return this

    val maxChars =
        (maxTokens.toLong() * ESTIMATED_BYTES_PER_TOKEN * length / encodeToByteArray().size).toInt()

    if (maxChars <= TRUNCATION_NOTICE.length) return OMITTED_NOTICE

    return limitTo(maxChars - TRUNCATION_NOTICE.length) + TRUNCATION_NOTICE
}

// the last call is the wrap-up's, so results after the one before it go to the wrap-up instead of buying
// another round.
private const val WRAP_UP_MODEL_CALLS = 1

internal fun outOfModelCalls(callsMade: Int, maxModelCalls: Int): Boolean = maxModelCalls - callsMade <= WRAP_UP_MODEL_CALLS

private fun cutOffResult(call: Part.ToolCall): ToolResult = ToolResult(call.id, call.name, CUT_OFF_CALL, isError = true)

private const val CUT_OFF_CALL =
    "Your reply reached the output limit before this call was complete, so it did not run. " +
            "Reissue it with shorter arguments, splitting long content across several calls."

internal fun repeatedCallMessage(tool: String, times: Int): String =
    "This is call $times of `$tool` with exactly these arguments in this turn. " +
            "If the result has not changed, repeating the call will not change it either: " +
            "use what it answered, change the arguments, or say what is in the way."

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
            "If you mean to answer with nothing, say so with `staySilent`. Do not reply with empty text."

internal const val PROMISE_NUDGE =
    "You told the user what you were about to do and then ended the turn without doing it. " +
            "They are waiting for exactly that: carry the work out now with the tools, then deliver the result. " +
            "If it cannot be done, say so through `sendMessage` instead of ending in silence."

// the model ended the turn with nothing in front of the user: no tool call, no caption text, an empty outbox.
// an announced plan is the promise, not the answer. nudged at most once, so a stubbornly empty model does not
// loop.
internal fun owesDelivery(
    reply: Message.Assistant,
    nudged: Boolean,
    outboxHasOutput: Boolean,
    silenceAllowed: Boolean,
): Boolean =
    !silenceAllowed && !nudged && reply.deliveredNothing() && !outboxHasOutput

// no tool call left to run and no text to use as a caption.
internal fun Message.Assistant.deliveredNothing(): Boolean = toolCalls.isEmpty() && text.isBlank()

// dropped before the nudge re-requests: openai refuses an assistant message with neither content nor tool
// calls, anthropic one with empty content (a blank text block) or ending in a thinking block.
internal fun MutableList<Message>.dropTrailingSilentAssistant() {
    while ((lastOrNull() as? Message.Assistant)?.deliveredNothing() == true) removeAt(lastIndex)
}
