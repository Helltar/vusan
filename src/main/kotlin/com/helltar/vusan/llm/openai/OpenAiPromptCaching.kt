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
 * On the Responses API that breakpoint asks for the twelve-hour lifetime, while the implicit tail keeps the
 * thirty-minute default: the developer block is written at two and a half times the input price instead of
 * one and a quarter, which pays once a conversation would otherwise rewrite it twice — two gaps longer than
 * half an hour within the day. Chat Completions is not documented to take the lifetime and keeps the default.
 *
 * A prompt that never repeats — the history recap, a look at a picture — gets explicit mode with nothing
 * marked, which the API documents as no caching at all; left to the implicit mode, the platform writes
 * the whole prompt at a quarter over the input price for a read nobody makes. Older models know neither
 * field and keep their own automatic caching, and a body with no developer block has nothing to mark and
 * is left to the platform's default, which is the implicit mode.
 *
 * A gateway ([viaGateway]) passes the fields on to whichever upstream serves the request, so it gets only
 * what every one of them takes, as OpenRouter's did on 2026-10-23 (gpt-5.6-luna): the breakpoint with no
 * mode named, since OpenRouter refuses `implicit` as a value and serves the unnamed one as it — the tail
 * was read back as it is here, where explicit mode kept the reads at the developer block — and without the
 * twelve-hour lifetime, an unknown parameter to Azure behind it.
 *
 * [stablePrefixes] are the item counts at which the replayed history ends, as far as the next turn will
 * replay it the same way. Each gets a breakpoint of the default lifetime on the last user message before
 * it: the implicit lookback finds a prefix only where an earlier request wrote one, and the history's end
 * moves into the middle of the next turn's prompt, where nothing was. A breakpoint on an assistant message
 * is taken and ignored. Measured live on 2026-10-24 (gpt-6-luna, and openai/gpt-5.6-luna through
 * OpenRouter): the second of two turns over a 2.5k-token history read 2610 tokens with the developer
 * block marked alone, 4627 with the history marked too. The implicit breakpoint, the developer block's and
 * two of these are the four cache writes a request may make.
 */
internal fun withPromptCacheOptions(
    body: JsonObject,
    cachePrompt: Boolean,
    viaGateway: Boolean = false,
    stablePrefixes: List<Int> = emptyList(),
): JsonObject {
    val model = (body["model"] as? JsonPrimitive)?.contentOrNull ?: return body
    if (!takesOpenAiPromptCacheOptions(model)) return body
    if (!cachePrompt) return JsonObject(body + ("prompt_cache_options" to EXPLICIT_MODE))

    val (key, textBlockType, systemBreakpoint) =
        when {
            body["input"] is JsonArray -> Triple("input", "input_text", if (viaGateway) EXPLICIT_BREAKPOINT else LONG_LIVED_BREAKPOINT)
            body["messages"] is JsonArray -> Triple("messages", "text", EXPLICIT_BREAKPOINT)
            else -> return body
        }

    val messages = markSystemBlock(body[key] as JsonArray, textBlockType, systemBreakpoint) ?: return body
    val marked = key to stablePrefixes.distinct().fold(messages) { marking, end -> markHistoryEnd(marking, end, textBlockType) }

    return if (viaGateway) JsonObject(body + marked) else JsonObject(body + marked + ("prompt_cache_options" to IMPLICIT_MODE))
}

/**
 * The cache options arrived with the GPT-5.6 generation; a generation's first models carry no minor version.
 * A gateway names the model under its vendor (`openai/gpt-5.6-luna`).
 */
internal fun takesOpenAiPromptCacheOptions(model: String): Boolean {
    val version = GPT_MODEL_VERSION.find(model.trim().lowercase()) ?: return false
    val major = version.groupValues[1].toInt()
    val minor = version.groupValues[2].toIntOrNull() ?: 0

    return major > CACHE_OPTIONS_MAJOR || (major == CACHE_OPTIONS_MAJOR && minor >= CACHE_OPTIONS_MINOR)
}

// the breakpoint goes on the last text block of the first developer or system message: the prefix every
// request of the deployment shares, and the one the lookup is sure to reach. an `additional_tools` item
// carries the developer role too, and takes no breakpoint
private fun markSystemBlock(messages: JsonArray, textBlockType: String, breakpoint: JsonObject): JsonArray? {
    val index = messages.indexOfFirst { it.isMessage && (it.role == "developer" || it.role == "system") }.takeIf { it >= 0 } ?: return null

    return markMessage(messages, index, textBlockType, breakpoint)
}

// a history that ends without a user message before [end] — the system prompt alone — has nothing to mark
private fun markHistoryEnd(messages: JsonArray, end: Int, textBlockType: String): JsonArray {
    val index = (0 until minOf(end, messages.size)).lastOrNull { messages[it].isMessage && messages[it].role == "user" } ?: return messages

    return markMessage(messages, index, textBlockType, EXPLICIT_BREAKPOINT) ?: messages
}

private fun markMessage(messages: JsonArray, index: Int, textBlockType: String, breakpoint: JsonObject): JsonArray? {
    val message = messages[index] as? JsonObject ?: return null
    val content = markLastTextBlock(message["content"], textBlockType, breakpoint) ?: return null

    return JsonArray(messages.mapIndexed { position, value -> if (position == index) JsonObject(message + ("content" to content)) else value })
}

private val JsonElement.role: String?
    get() = ((this as? JsonObject)?.get("role") as? JsonPrimitive)?.contentOrNull

// a responses input item names its type, a chat completions message has none
private val JsonElement.isMessage: Boolean
    get() = (((this as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull ?: "message") == "message"

// a chat completions message may carry its text as a bare string; a breakpoint needs a block to sit on
private fun markLastTextBlock(content: JsonElement?, textBlockType: String, breakpoint: JsonObject): JsonArray? {
    if (content is JsonPrimitive && content.isString) {
        return JsonArray(listOf(markedTextBlock(buildJsonObject { put("type", textBlockType); put("text", content.content) }, breakpoint)))
    }

    val blocks = content as? JsonArray ?: return null
    val index = blocks.indexOfLast { (((it as? JsonObject)?.get("type")) as? JsonPrimitive)?.contentOrNull == textBlockType }
    if (index < 0) return null

    val block = blocks[index] as? JsonObject ?: return null

    return JsonArray(blocks.mapIndexed { blockIndex, value -> if (blockIndex == index) markedTextBlock(block, breakpoint) else value })
}

private fun markedTextBlock(block: JsonObject, breakpoint: JsonObject): JsonObject = JsonObject(block + ("prompt_cache_breakpoint" to breakpoint))

private const val CACHE_OPTIONS_MAJOR = 5
private const val CACHE_OPTIONS_MINOR = 6
private val GPT_MODEL_VERSION = Regex("""^(?:openai/)?gpt-(\d+)(?:\.(\d+))?""")
private val EXPLICIT_BREAKPOINT = buildJsonObject { put("mode", "explicit") }
private val LONG_LIVED_BREAKPOINT = buildJsonObject { put("mode", "explicit"); put("ttl", "12h") }
private val EXPLICIT_MODE = buildJsonObject { put("mode", "explicit") }
private val IMPLICIT_MODE = buildJsonObject { put("mode", "implicit") }
