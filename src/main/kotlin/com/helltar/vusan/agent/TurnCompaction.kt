package com.helltar.vusan.agent

import com.helltar.vusan.llm.Message
import com.helltar.vusan.llm.Part
import com.helltar.vusan.llm.ToolDefinition
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val FOLDED_RESULT = "[result dropped to make room in the context; its label still holds the whole of it for any argument that takes a label, or call the tool again]"

// what a message costs beyond its text: the role, the ids, the framing the provider adds
internal const val MESSAGE_OVERHEAD_TOKENS = 12

// what a tool schema costs beyond its text: the JSON framing of its parameters
internal const val TOOL_SCHEMA_OVERHEAD_TOKENS = 32

// arguments shorter than this say what the call was — a path, a query — and are kept whole
private const val KEPT_ARGUMENTS_CHARS = 200

/**
 * The turn's own messages folded until the request fits [ceilingTokens]: the oldest batch of tool
 * results after [turnStart] is replaced by a stub, together with the long arguments of the calls it
 * answered, then the next, and so on. The latest batch is never touched, nor anything before the turn —
 * the stored history was already planned to fit. Returns `null` when nothing had to change, and the
 * best it could do when even that is not enough: the request then fails as it would have, and the
 * runner answers for it.
 *
 * An edited message costs the provider's cache from there on and, on Anthropic, the thinking bound to
 * what came after it — the price of a turn that would otherwise die with its work undelivered.
 */
internal fun List<Message>.foldedToFit(ceilingTokens: Int, turnStart: Int): List<Message>? {
    var tokens = sumOf(::estimateTokens)
    if (tokens <= ceilingTokens) return null

    val folded = toMutableList()
    val latestResults = folded.indexOfLast { it is Message.ToolResults }

    for (index in turnStart until folded.size) {
        if (tokens <= ceilingTokens) break

        val results = folded[index] as? Message.ToolResults ?: continue
        if (index == latestResults || results.isFolded()) continue

        val calls = folded[index - 1] as? Message.Assistant

        tokens -= estimateTokens(results) + calls?.let(::estimateTokens).orZero()
        folded[index] = results.folded()
        calls?.let { folded[index - 1] = it.withFoldedArguments() }
        tokens += estimateTokens(folded[index]) + calls?.let { estimateTokens(folded[index - 1]) }.orZero()
    }

    return folded
}

/** What a message costs in the estimate the budget is counted in; an image is a size, not a text. */
internal fun estimateTokens(message: Message): Int =
    MESSAGE_OVERHEAD_TOKENS +
            when (message) {
                is Message.System -> estimateTokens(message.text)
                is Message.User -> message.parts.sumOf(::estimateTokens)
                is Message.Assistant -> message.parts.sumOf(::estimateTokens)
                is Message.ToolResults -> message.results.sumOf { estimateTokens(it.output) + MESSAGE_OVERHEAD_TOKENS }
            }

internal fun estimateTokens(tools: List<ToolDefinition>): Int =
    tools.sumOf { estimateTokens("${it.name} ${it.description} ${it.parameters}") + TOOL_SCHEMA_OVERHEAD_TOKENS }

private fun estimateTokens(part: Part): Int =
    when (part) {
        is Part.Text -> estimateTokens(part.text)
        is Part.ToolCall -> estimateTokens(part.arguments.toString()) + MESSAGE_OVERHEAD_TOKENS
        is Part.Reasoning -> estimateTokens(part.raw.toString())
        is Part.Image -> part.bytes.size / ESTIMATED_BYTES_PER_TOKEN
    }

internal fun Message.ToolResults.isFolded(): Boolean = results.all { it.output.endsWith(FOLDED_RESULT) }

// the stub keeps the result's label: the shelf still holds the whole of it there, so a later call can take
// it without the tool running again
private fun Message.ToolResults.folded(): Message.ToolResults =
    Message.ToolResults(results.map { result -> result.copy(output = result.output.resultLabelOrNull()?.let { "[$it] $FOLDED_RESULT" } ?: FOLDED_RESULT) })

// the call stays answered under its id; only what it carried goes, and the stub says it ran as issued
private fun Message.Assistant.withFoldedArguments(): Message.Assistant =
    Message.Assistant(
        parts.map { part ->
            val arguments = (part as? Part.ToolCall)?.arguments?.toString()

            if (part is Part.ToolCall && arguments != null && arguments.length > KEPT_ARGUMENTS_CHARS) {
                part.copy(arguments = buildJsonObject { put("_dropped", "arguments of ${arguments.length} chars dropped to make room; the call ran as issued") })
            } else {
                part
            }
        },
    )

private fun Int?.orZero(): Int = this ?: 0
