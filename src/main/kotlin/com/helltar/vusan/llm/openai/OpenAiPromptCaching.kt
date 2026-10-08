package com.helltar.vusan.llm.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * What a request on the GPT-5.6 generation says about its prompt cache, the generation where the
 * platform stopped deciding alone.
 *
 * Implicit mode is the one an agent's loop wants: the platform puts a breakpoint after the latest
 * eligible message — a user message, or the last of a run of tool results — and looks back from there
 * over the twenty message endings before it and the end of the opening developer block. So every
 * iteration reads the tool results the one before it appended, and the next turn, whose history is
 * replayed from storage in another shape, still reads the system prefix. An explicit breakpoint stays
 * on that developer block all the same: the first two explicit breakpoints are looked up whatever a long
 * turn pushed out of the lookback window. Measured live on 2026-10-08 (gpt-5.6-luna, a three-call turn):
 * reads of 0 → 2512 → 2561, against 0 → 2512 → 2512 with explicit breakpoints alone.
 *
 * A prompt that never repeats — the history recap, a look at a picture — gets explicit mode with nothing
 * marked, which the API documents as no caching at all; left to the implicit mode, the platform writes
 * the whole prompt at a quarter over the input price for a read nobody makes. Older models know neither
 * field and keep their own automatic caching, and a body with no developer block has nothing to mark and
 * is left to the platform's default, which is the implicit mode.
 */
internal fun withPromptCacheOptions(body: JsonObject, cachePrompt: Boolean): JsonObject {
    val model = (body["model"] as? JsonPrimitive)?.contentOrNull ?: return body
    if (!takesOpenAiPromptCacheOptions(model)) return body
    if (!cachePrompt) return JsonObject(body + ("prompt_cache_options" to EXPLICIT_MODE))

    val marked =
        (body["input"] as? JsonArray)
            ?.let { markSystemBlock(it, "input_text") }
            ?.let { "input" to it }
            ?: (body["messages"] as? JsonArray)
                ?.let { markSystemBlock(it, "text") }
                ?.let { "messages" to it }
            ?: return body

    return JsonObject(body + marked + ("prompt_cache_options" to IMPLICIT_MODE))
}

/** The cache options arrived with the GPT-5.6 generation; a generation's first models carry no minor version. */
internal fun takesOpenAiPromptCacheOptions(model: String): Boolean {
    val version = GPT_MODEL_VERSION.find(model.trim().lowercase()) ?: return false
    val major = version.groupValues[1].toInt()
    val minor = version.groupValues[2].toIntOrNull() ?: 0

    return major > CACHE_OPTIONS_MAJOR || major == CACHE_OPTIONS_MAJOR && minor >= CACHE_OPTIONS_MINOR
}

// the breakpoint goes on the last text block of the first developer or system message: the prefix every
// request of the deployment shares, and the one the lookup is sure to reach
private fun markSystemBlock(messages: JsonArray, textBlockType: String): JsonArray? {
    val index = messages.indexOfFirst { it.role == "developer" || it.role == "system" }.takeIf { it >= 0 } ?: return null
    val message = messages[index] as? JsonObject ?: return null
    val content = markLastTextBlock(message["content"], textBlockType) ?: return null

    return JsonArray(messages.mapIndexed { position, value -> if (position == index) JsonObject(message + ("content" to content)) else value })
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

private fun markedTextBlock(block: JsonObject): JsonObject = JsonObject(block + ("prompt_cache_breakpoint" to EXPLICIT_BREAKPOINT))

private const val CACHE_OPTIONS_MAJOR = 5
private const val CACHE_OPTIONS_MINOR = 6
private val GPT_MODEL_VERSION = Regex("""^gpt-(\d+)(?:\.(\d+))?""")
private val EXPLICIT_BREAKPOINT = buildJsonObject { put("mode", "explicit") }
private val EXPLICIT_MODE = buildJsonObject { put("mode", "explicit") }
private val IMPLICIT_MODE = buildJsonObject { put("mode", "implicit") }
