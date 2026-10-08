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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class ToolEvent(
    val toolCallId: String,
    val toolName: String,
    val args: String,
    val output: String,
    val isError: Boolean,
)

/**
 * The model, or a safety classifier in front of it, declined the turn's request; [reason] is the
 * provider's when it gave one. Asking again with the same prompt is declined again.
 */
class ModelRefusal(val reason: String?) : RuntimeException("the model declined the request" + reason?.let { ": $it" }.orEmpty())

/**
 * One turn of the agent: the model is asked, its tool calls are run and answered, and so on until it
 * replies without one — or until the turn runs out of model calls and is landed instead.
 *
 * The calls of one batch run in order, except that read-only ones standing next to each other run side
 * by side: a search changes nothing, so three of them need not wait for one another, while anything that
 * writes keeps its place in the batch. Results are recorded in batch order either way.
 *
 * Guards against flaky models, in three places:
 * - a tool call that arrives with no arguments at all for a tool that takes them (flaky models emit
 *   empty-arg siblings when they try to call tools in parallel) is answered with a validation error
 *   instead of being executed, so the follow-up request stays well-formed and the model reissues it;
 * - a turn that ends having delivered nothing — no `sendMessage`, media or reaction, and empty assistant
 *   text (flaky providers return an empty completion after a batch of tool results) — gets one nudge to
 *   actually deliver before finishing, so a full turn of research does not collapse into silence;
 * - a turn that announced its plan and then ended without doing the work is sent back to it once: the
 *   announcement reached the chat, so the user is waiting on exactly what it promised.
 *
 * A turn whose own pile of tool results would no longer fit the window has its oldest results folded
 * away before the next request (`foldedToFit`), so a long build keeps going instead of dying on the
 * context limit with its work undelivered.
 *
 * Landing: the last model call of the turn is reserved for a wrap-up request in which no tool may be
 * called, so the model cannot spend it on one more search, and its answer — written from what it gathered,
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
    // what the whole request may measure, in the estimate the budget is counted in, before the turn's own
    // results are folded away
    private val promptTokenCeiling: Int,
    private val scope: ConversationScope,
    private val mayStaySilent: Boolean,
    private val toolEvents: (ToolEvent) -> Unit,
    private val tokenUsage: (TokenUsage) -> Unit,
    private val onToolStarting: (activity: ToolActivity?) -> Unit,
) {

    private var calls = 0
    private var eventSeq = 0

    // whether the model told the user what it was about to do, and whether it then did anything at all
    private var announced = false
    private var workedSinceAnnouncement = false

    /** Runs the turn for [currentTurn] and returns the model's closing text, which may be empty. */
    suspend fun run(currentTurn: String): String {
        val messages = history.toMutableList()
        val turnStart = messages.size
        messages += Message.User(currentTurn)

        var nudged = false
        var lowBudgetWarned = false

        while (true) {
            val tools = catalog.visibleDefinitions()
            foldToFit(messages, turnStart, tools)

            val reply = request(messages, tools)
            val message = reply.message
            messages += message

            if (message.toolCalls.isEmpty()) {
                // a turn nobody called the bot into may end in silence, but only on its first reply: a
                // model that sees the message was not for it says nothing before it calls anything.
                val silenceAllowed = mayStaySilent && calls == 1
                val promised = announced && !workedSinceAnnouncement

                if (!promised && !owesDelivery(message, nudged, outbox.hasAnswered, silenceAllowed)) return message.text
                if (nudged) return message.text

                messages.dropTrailingSilentAssistant()

                // the nudge is a model call like any other, and the last one is the wrap-up's
                if (outOfModelCalls(calls, maxModelCalls)) return wrapUp(messages)

                nudged = true

                if (promised) log.info { "model announced a plan and stopped for $scope; sending it back to the work" }

                messages += Message.User(if (promised) PROMISE_NUDGE else DELIVER_NUDGE)
                continue
            }

            // this batch still runs — it is already paid for, and it may carry the delivery call — but once
            // the budget is this thin the results go to the wrap-up instead of buying another tool round.
            // a batch the output ceiling cut short does not: its last call may have been cut with it, and
            // reads like a whole one, so the model is told why none ran and reissues what it needs.
            val results =
                if (reply.stopReason == StopReason.MAX_TOKENS) {
                    message.toolCalls.map(::cutOffResult)
                } else {
                    executeBatch(message.toolCalls)
                }

            messages += Message.ToolResults(results)

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

    private suspend fun request(messages: List<Message>, tools: List<ToolDefinition>, mayCallTools: Boolean = true): Reply {
        logPromptDump(messages, model.id, tools.map { it.name })
        calls++

        // a copy: the list grows as the turn goes on, and a client may keep the request it was handed
        val reply = client.complete(model, ChatRequest(messages.toList(), tools, options, mayCallTools))

        reply.usage?.let(tokenUsage)

        // what a declined reply holds is not an answer — a classifier may have stopped it mid-call — and the
        // same prompt is declined again, so the turn ends here and the runner says why.
        if (reply.stopReason == StopReason.REFUSAL) throw ModelRefusal(reply.refusal)

        if (reply.stopReason == StopReason.MAX_TOKENS) {
            log.warn { "model hit its output ceiling for $scope; ${reply.message.toolCalls.size} tool call(s) in the reply will not run" }
        }

        return reply
    }

    // the turn's own pile is folded when the whole request would not fit: the oldest results first, with
    // the long arguments they answered, never the latest batch. the stored history before the turn was
    // planned to fit and is left alone.
    private fun foldToFit(messages: MutableList<Message>, turnStart: Int, tools: List<ToolDefinition>) {
        val folded = messages.foldedToFit(promptTokenCeiling - estimateTokens(tools), turnStart) ?: return
        val foldedResults = folded.count { it is Message.ToolResults && it.results.all { result -> result.output == FOLDED_RESULT } }

        log.warn { "turn history folded for $scope: ${messages.size} messages over ~$promptTokenCeiling tokens, $foldedResults result batch(es) dropped" }

        messages.clear()
        messages += folded
    }

    private suspend fun wrapUp(messages: MutableList<Message>): String {
        log.warn { "model calls spent for $scope (limit $maxModelCalls); answering with what the turn already gathered" }

        messages += Message.User(TOOL_BUDGET_WRAP_UP)

        // the tools stay defined, since the calls the turn made are replayed with them, but none may be called
        val answer = request(messages, catalog.visibleDefinitions(), mayCallTools = false).message.text

        // queue it rather than leave it as the run's trailing text: a turn that already reacted or sent a
        // message has that text dropped as duplicate chatter, and here it is the whole answer.
        if (answer.isNotBlank() && !outbox.enqueueText(answer)) {
            log.warn { "no room left in the outbox for the wrap-up answer for $scope" }
        }

        return answer
    }

    // read-only calls standing next to each other run side by side, everything else in order; the results
    // are recorded in batch order either way, so the budget and the events read as if it ran one by one.
    private suspend fun executeBatch(batch: List<Part.ToolCall>): List<ToolResult> {
        val outcomes = mutableListOf<Pair<Part.ToolCall, Outcome>>()

        for (run in batch.runs()) {
            val performed =
                if (run.size > 1) {
                    coroutineScope { run.map { call -> async { perform(call) } }.awaitAll() }
                } else {
                    listOf(perform(run.single()))
                }

            outcomes += run.zip(performed)
        }

        return outcomes.map { (call, outcome) -> record(call, outcome) }
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

    private suspend fun perform(call: Part.ToolCall): Outcome {
        val tool = catalog.find(call.name)

        onToolStarting(toolActivityFor(call.name))

        val (output, isError) =
            when {
                tool == null ->
                    "There is no tool named `${call.name}`. The tools you can call are: ${catalog.tools.joinToString { it.name }}." to true

                call.arguments.isEmpty() && tool.requiredParameters.isNotEmpty() ->
                    garbledCallMessage(tool.name, tool.requiredParameters) to true

                else -> runTool(tool.name) { tool.call(call.arguments) }
            }

        log.info { "tool call: name=[${call.name}] error=$isError args=[${call.arguments.toString().collapseWhitespaceAndCap(TOOL_LOG_ARGS_MAX_CHARS).orEmpty()}]" }

        return Outcome(output, isError)
    }

    private fun record(call: Part.ToolCall, outcome: Outcome): ToolResult {
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

        // the model sees a result bounded to what is left of the run's reserve; history keeps it whole
        val bounded = outcome.output.boundedToolText(toolBudget.remainingTokens)
        toolBudget.spend(estimateTokens(bounded))

        return ToolResult(call.id, call.name, bounded, outcome.isError)
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

    private class Outcome(val output: String, val isError: Boolean)

    private companion object {
        const val TOOL_LOG_ARGS_MAX_CHARS = 300
        val log = KotlinLogging.logger {}
    }
}

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

private fun cutOffResult(call: Part.ToolCall): ToolResult = ToolResult(call.id, call.name, CUT_OFF_CALL, isError = true)

private const val CUT_OFF_CALL =
    "Your reply reached the output limit before this call was complete, so it did not run. " +
            "Reissue it with shorter arguments, splitting long content across several calls."

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

internal const val PROMISE_NUDGE =
    "You told the user what you were about to do and then ended the turn without doing it. " +
            "They are waiting for exactly that: carry the work out now with the tools, then deliver the result. " +
            "If it cannot be done, say so through `sendMessage` instead of ending in silence."

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

// a reply that delivered nothing is dropped before the nudge re-requests: openai refuses an assistant
// message with neither content nor tool calls, and anthropic one whose content is left empty — which is
// what a blank text block becomes there — or ends in a thinking block.
internal fun MutableList<Message>.dropTrailingSilentAssistant() {
    while ((lastOrNull() as? Message.Assistant)?.deliveredNothing() == true) removeAt(lastIndex)
}
