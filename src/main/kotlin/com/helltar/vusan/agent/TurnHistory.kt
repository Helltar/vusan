package com.helltar.vusan.agent

import com.helltar.vusan.agent.conversation.ChatRole
import com.helltar.vusan.agent.conversation.ChatTurn
import com.helltar.vusan.agent.conversation.toolCallArgsForStorage
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.isEffectivelyBlank
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.OutboxItem
import com.helltar.vusan.tools.choice.InlineChoiceTools
import com.helltar.vusan.tools.message.MessageTools

// what a finished turn leaves behind for the next one to read. history is not a transcript of the run:
// a delivery tool's payload is already the assistant text stored beside it, and a long tail of tool
// results would crowd out the words that matter. what survives is the user's entry, a bounded slice of
// the tools, and one assistant row.

private const val TOOL_OUTPUT_MAX_CHARS = 4_000
private const val TOOL_EVENTS_MAX_COUNT = 8
private const val TOOL_EVENTS_MAX_CHARS = 12_000

// trailing assistant text after a delivery tool is duplicate chatter and is dropped — but an
// announcement is not the answer, so it must not silence the closing text that is.
internal fun extractFinalComment(answer: String, outputs: List<OutboxItem>): String? =
    answer.trim()
        .takeUnless { it.isEffectivelyBlank() }
        ?.takeUnless {
            outputs.filterNot { item -> item.delivered }.any {
                it.output is BotOutput.Voice ||
                        it.output is BotOutput.VideoNote ||
                        it.output is BotOutput.Text ||
                        it.output is BotOutput.RichMessage ||
                        it.output is BotOutput.InlineChoice ||
                        it.output is BotOutput.Reaction
            }
        }

internal fun assistantTextForHistory(outputs: List<OutboxItem>, comment: String?): String? {
    val parts =
        buildList {
            addAll(
                outputs.mapNotNull {
                    when (val output = it.output) {
                        is BotOutput.Text -> output.text
                        is BotOutput.InlineChoice -> output.historyText()
                        is BotOutput.RichMessage -> output.markdown
                        else -> null
                    }
                }
            )
            comment?.let(::add)
        }

    return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}

// tools whose payload is fully duplicated by the assistant text row. skipping their
// matching TOOL_CALL/TOOL_RESULT pair avoids storing (and replaying) the same content twice.
// the Koog runtime registers each tool under its function name (no tool here sets @Tool(customName)),
// so a function reference stays in sync with the registered name across renames.
private val TEXT_DUPLICATING_TOOLS =
    setOf(
        MessageTools::sendMessage.name,
        MessageTools::sendRichMessage.name,
        MessageTools::announcePlan.name,
        InlineChoiceTools::askWithButtons.name
    )

private fun BotOutput.InlineChoice.historyText(): String =
    question + "\n\n" + options.joinToString("\n") { "• $it" }

internal fun buildTurns(userEntry: String, toolEvents: List<ToolEvent>, assistantText: String?): List<ChatTurn> =
    buildList {
        add(ChatTurn(role = ChatRole.USER, content = userEntry))

        for (event in toolEvents.forHistory()) {

            add(
                ChatTurn(
                    role = ChatRole.TOOL_CALL,
                    content = toolCallArgsForStorage(event.args),
                    toolCallId = event.toolCallId,
                    toolName = event.toolName
                )
            )

            add(
                ChatTurn(
                    role = ChatRole.TOOL_RESULT,
                    content = event.output.collapseWhitespaceAndCap(TOOL_OUTPUT_MAX_CHARS).orEmpty(),
                    toolCallId = event.toolCallId,
                    toolName = event.toolName,
                    toolIsError = event.isError
                )
            )
        }

        if (!assistantText.isNullOrBlank()) {
            add(ChatTurn(role = ChatRole.ASSISTANT, content = assistantText))
        }
    }

private fun List<ToolEvent>.forHistory(): List<ToolEvent> {
    val selected = ArrayDeque<ToolEvent>()
    var usedChars = 0

    for (event in asReversed()) {
        if (event.toolName in TEXT_DUPLICATING_TOOLS) continue

        val args = toolCallArgsForStorage(event.args)
        val output = event.output.collapseWhitespaceAndCap(TOOL_OUTPUT_MAX_CHARS).orEmpty()
        val cost = args.length + output.length

        if (selected.isNotEmpty() && (selected.size >= TOOL_EVENTS_MAX_COUNT || usedChars + cost > TOOL_EVENTS_MAX_CHARS)) {
            continue
        }

        selected.addFirst(event)
        usedChars += cost
    }

    return selected.toList()
}
