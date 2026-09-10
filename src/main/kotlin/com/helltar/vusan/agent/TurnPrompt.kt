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
 * All of it rides in one user-role message, including the clock and the sticker index. A second
 * system message would read as a higher-priority instruction — wrong for context assembled out of
 * what people sent — and koog's Anthropic and Google clients hoist every system message into the
 * top-level system field anyway, so a block placed here would not stay here on those providers.
 */
internal fun currentTurnPrompt(
    userInput: String,
    context: RequestContext,
    previousExchangeAt: Instant? = null,
    userMemory: List<MemoryEntry>,
    chatMemory: List<MemoryEntry>,
    recentChat: String? = null,
    stickerCatalog: String? = null,
    toolGroups: String? = null
): String =
    buildList {
        add(currentTimeBlock())
        add(context.toPromptBlock(previousExchangeAt))
        memoryBlock("user_memory", userMemory)?.let(::add)
        memoryBlock("group_memory", chatMemory)?.let(::add)
        // it changes with what this conversation has already loaded, so it cannot live in the system
        // block: that block is the one prefix every request of the deployment shares.
        toolGroups?.takeIf { it.isNotBlank() }?.let { add(xmlBlock("tool_groups", it)) }
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

// renders memory as `#id content` lines so the model can reference an id when calling `forgetMemory`.
// an entry is written from what somebody said, and `group_memory` is editable by every member of the
// chat, so it is quoted text and gets the same defusing.
private fun memoryBlock(
    tag: String,
    entries: List<MemoryEntry>
): String? =
    entries
        .takeIf { it.isNotEmpty() }
        ?.joinToString("\n") { "#${it.id} ${it.content.neutralizePromptBlocks()}" }
        ?.let { xmlBlock(tag, it) }
