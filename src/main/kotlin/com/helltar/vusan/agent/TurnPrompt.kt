package com.helltar.vusan.agent

import com.helltar.vusan.agent.memory.MemoryEntry
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.request.RequestContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Everything the model is shown for this turn, request last.
 *
 * All in one user-role message, clock and sticker index included. A second system message would read as a
 * higher-priority instruction, wrong for context built from what people sent, and OpenRouter hands one inside
 * the conversation to Claude as the assistant's own words, so the Anthropic client lifts every system message
 * into its system field.
 */
internal fun currentTurnPrompt(
    userInput: String,
    context: RequestContext,
    previousExchangeAt: Instant? = null,
    lastAnswerOfStretch: Boolean = false,
    leftAloneUntil: Instant? = null,
    userMemory: List<MemoryEntry>,
    chatMemory: List<MemoryEntry>,
    diary: String? = null,
    recentChat: String? = null,
    stickerCatalog: String? = null,
    toolGroups: String? = null,
    fallbackModel: String? = null,
): String =
    buildList {
        add(currentTimeBlock())
        add(context.toPromptBlock(previousExchangeAt, lastAnswerOfStretch, leftAloneUntil))
        memoryBlock("user_memory", userMemory)?.let(::add)
        memoryBlock("group_memory", chatMemory)?.let(::add)
        diary?.takeIf { it.isNotBlank() }?.let { add(xmlBlock("diary", it)) }
        // it changes with what the conversation loaded, so it cannot live in the system block, the prefix
        // every request shares.
        toolGroups?.takeIf { it.isNotBlank() }?.let { add(xmlBlock("tool_groups", it)) }
        // only while the primary is out, so it costs nothing on ordinary turns and the system prompt's model
        // id stays the answer when true.
        fallbackModel?.takeIf { it.isNotBlank() }?.let { add(xmlBlock("current_model", it)) }
        stickerCatalog?.takeIf { it.isNotBlank() }?.let(::add)
        recentChat?.takeIf { it.isNotBlank() }?.let { add(xmlBlock("recent_chat", it)) }
        add(userInput)
    }.joinToString("\n\n")

private val LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
private val DAY_OF_WEEK = DateTimeFormatter.ofPattern("EEEE")

private fun currentTimeBlock(): String {
    val timezone = ZoneId.systemDefault()
    val now = ZonedDateTime.now(timezone)

    return xmlBlock("current_time", "${LOCAL_DATE_TIME.format(now)} ${timezone.id} (${DAY_OF_WEEK.format(now)})")
}

// memory as `#id content` lines, so the model can reference an id in `forgetMemory`. entries come from what
// people said, and any member can edit `group_memory`, so it is defused as quoted text.
private fun memoryBlock(
    tag: String,
    entries: List<MemoryEntry>,
): String? =
    entries
        .takeIf { it.isNotEmpty() }
        ?.joinToString("\n") { "#${it.id} ${it.content.neutralizePromptBlocks()}" }
        ?.let { xmlBlock(tag, it) }
