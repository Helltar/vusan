package com.helltar.vusan.llm.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Marks the two prefixes a turn re-sends, well inside the four cache writes a request may make: the
 * system instructions, stable for the life of the deployment, and the last user message, stable for
 * every iteration of one agent run — the tool loop re-sends the whole history on each of them.
 *
 * GPT-5.6-era caching puts its implicit breakpoint at the latest user or tool message, with no fallback
 * to an earlier matching prefix, which in an agent's request is after the changing history, time, memory
 * and tool results: zero reads, repeated writes, a stable system prompt notwithstanding. Explicit mode
 * says where to cut instead. OpenAI reads from the longest matching prefix, so the turn that grew the
 * history still reads the system one. A tool-free request — the history recap — marks the system
 * block alone: its user message never repeats, so marking it would buy a write nobody reads. Older
 * models keep automatic caching, and a body with nothing to mark is left as it was, because `explicit`
 * mode without a marked block disables caching outright.
 */
internal fun withExplicitPromptCacheBreakpoints(body: JsonObject): JsonObject {
    val model = (body["model"] as? JsonPrimitive)?.contentOrNull ?: return body
    if (!supportsExplicitOpenAiPromptCaching(model)) return body

    val markCurrentTurn = (body["tools"] as? JsonArray)?.isNotEmpty() == true

    val marked =
        (body["input"] as? JsonArray)
            ?.let { markCachedPrefixes(it, "input_text", markCurrentTurn) }
            ?.let { "input" to it }
            ?: (body["messages"] as? JsonArray)
                ?.let { markCachedPrefixes(it, "text", markCurrentTurn) }
                ?.let { "messages" to it }
            ?: return body

    return JsonObject(body + marked + ("prompt_cache_options" to EXPLICIT_CACHE_CONTROL))
}

/** Explicit breakpoints arrived with the GPT-5.6 generation; a generation's first models carry no minor version. */
internal fun supportsExplicitOpenAiPromptCaching(model: String): Boolean {
    val version = GPT_MODEL_VERSION.find(model.trim().lowercase()) ?: return false
    val major = version.groupValues[1].toInt()
    val minor = version.groupValues[2].toIntOrNull() ?: 0

    return major > EXPLICIT_CACHING_MAJOR || major == EXPLICIT_CACHING_MAJOR && minor >= EXPLICIT_CACHING_MINOR
}

private fun markCachedPrefixes(messages: JsonArray, textBlockType: String, markCurrentTurn: Boolean): JsonArray? {
    val systemIndex = messages.indexOfFirst { it.role == "developer" || it.role == "system" }
    val currentTurnIndex = if (markCurrentTurn) messages.indexOfLast { it.role == "user" } else -1

    val marked = messages.toMutableList()
    var markedAny = false

    for (index in setOf(systemIndex, currentTurnIndex).filter { it >= 0 }) {
        val message = marked[index] as? JsonObject ?: continue
        val content = markLastTextBlock(message["content"], textBlockType) ?: continue

        marked[index] = JsonObject(message + ("content" to content))
        markedAny = true
    }

    return if (markedAny) JsonArray(marked) else null
}

private val JsonElement.role: String?
    get() = ((this as? JsonObject)?.get("role") as? JsonPrimitive)?.contentOrNull

// a chat completions message may carry its text as a bare string; a breakpoint needs a block to sit on
private fun markLastTextBlock(content: JsonElement?, textBlockType: String): JsonArray? {
    if (content is JsonPrimitive && content.isString) {
        return JsonArray(listOf(markedTextBlock(buildJsonObject { put("type", textBlockType); put("text", content.content) })))
    }

    val blocks = content as? JsonArray ?: return null
    val index = blocks.indexOfLast { (((it as? JsonObject)?.get("type")) as? JsonPrimitive)?.contentOrNull == textBlockType }
    if (index < 0) return null

    val block = blocks[index] as? JsonObject ?: return null

    return JsonArray(blocks.mapIndexed { blockIndex, value -> if (blockIndex == index) markedTextBlock(block) else value })
}

private fun markedTextBlock(block: JsonObject): JsonObject = JsonObject(block + ("prompt_cache_breakpoint" to EXPLICIT_CACHE_CONTROL))

private const val EXPLICIT_CACHING_MAJOR = 5
private const val EXPLICIT_CACHING_MINOR = 6
private val GPT_MODEL_VERSION = Regex("""^gpt-(\d+)(?:\.(\d+))?""")
private val EXPLICIT_CACHE_CONTROL = buildJsonObject { put("mode", "explicit") }
