package com.helltar.vusan.llm

import kotlinx.serialization.json.JsonObject

/**
 * How hard a reasoning model thinks before answering, spelled as the APIs spell it. Models accept different
 * ranges (OpenAI `none` to `max`, Anthropic `low` to `max`, Google `none` to `high`) and answer the rest with
 * a 400, so the resolvers check what they can at startup.
 */
enum class ReasoningEffort {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX;

    /** The value a request carries, also how the Codex model catalog lists it. */
    val requestValue: String
        get() = name.lowercase()
}

/**
 * What a request asks of the model beyond its messages, one shape for every provider: a client reads the
 * fields its API has.
 *
 * [cachePrompt] asks the provider to keep the prompt's prefix (Anthropic's `cache_control`, OpenAI's explicit
 * breakpoints); off for a prompt that never repeats, since the write would buy no read. [promptCacheKey] is
 * OpenAI's routing key to the machine holding the prefix, sent only to servers that know it; OpenRouter also
 * takes it as `session_id` to keep a conversation on one upstream.
 *
 * [fallBackOnRefusal] asks the fallback provider again when the primary declines, for calls whose refusal is
 * one vendor's filter rather than a verdict that would hold anywhere. No provider reads it.
 */
data class RequestOptions(
    val reasoningEffort: ReasoningEffort? = null,
    val verbosity: String? = null,
    val serviceTier: String? = null,
    val promptCacheKey: String? = null,
    val parallelToolCalls: Boolean? = null,
    val maxOutputTokens: Int? = null,
    val cachePrompt: Boolean = true,
    val fallBackOnRefusal: Boolean = false,
) {

    /**
     * The same options keyed to this conversation. Reads match the latest prefixes written under a key, so
     * one key per deployment lets busy chats evict each other. The conversation is hashed: the key only
     * routes, a collision costs nothing since a read needs an exact prefix match, and a messenger's user id
     * has no business at a provider. Options without a key keep none.
     */
    fun forConversation(conversation: String): RequestOptions =
        promptCacheKey?.let { copy(promptCacheKey = "$it-${conversation.hashCode().toUInt().toString(HEX_RADIX)}") } ?: this

    private companion object {
        const val HEX_RADIX = 16
    }
}

/**
 * A tool as the model is told about it: name, purpose and the JSON schema of its arguments.
 *
 * A [deferred] tool is declared but not offered until a [Message.ToolsOffered] names it. Declaring it from
 * the first request on keeps the start of the prompt unchanged when it is offered later.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val deferred: Boolean = false,
)

/**
 * [mayCallTools] `false` keeps [tools] in the request while forbidding calls: Anthropic requires tool
 * definitions to replay tool calls, and the tool list is part of the cached prefix on both APIs, so dropping
 * it would cost a 400 or a cache miss.
 *
 * [stablePrefixes] are counts of [messages] after which the next turn's request still repeats everything
 * before unchanged. A client placing cache breakpoints marks each when [RequestOptions.cachePrompt] is set,
 * so the next turn reads its history from cache; the turn's own calls read each other through the request's
 * own breakpoint.
 */
data class ChatRequest(
    val messages: List<Message>,
    val tools: List<ToolDefinition> = emptyList(),
    val options: RequestOptions = RequestOptions(),
    val mayCallTools: Boolean = true,
    val stablePrefixes: List<Int> = emptyList(),
) {

    init {
        require(messages.isNotEmpty()) { "a request carries at least one message" }
        require(stablePrefixes.all { it in 1..messages.size }) { "a stable prefix ends inside the request" }
    }

    /**
     * What an endpoint that cannot take tools mid-conversation lists up front: every tool not deferred, plus
     * the deferred ones offered by now.
     */
    fun toolsListedUpFront(): List<ToolDefinition> {
        val offered = messages.filterIsInstance<Message.ToolsOffered>().flatMapTo(mutableSetOf()) { it.names }

        return tools.filter { !it.deferred || it.name in offered }.map { it.copy(deferred = false) }
    }
}

enum class StopReason {
    /** The model finished its answer. */
    END,

    /** The model wants its tool calls answered. */
    TOOL_CALLS,

    /** The output ceiling cut the answer short, tool call arguments included. */
    MAX_TOKENS,

    /** The model or a safety classifier declined; the same prompt is declined again. */
    REFUSAL,

    OTHER
}

data class TokenUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    // what the provider read from and wrote to its prompt cache, where it says
    val cacheReadTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    // the part of those writes kept for the longer lifetime, where the provider splits them
    val cacheWriteLongTokens: Int? = null,
) {

    val totalTokens: Int?
        get() = if (inputTokens == null && outputTokens == null) null else (inputTokens ?: 0) + (outputTokens ?: 0)
}

data class Reply(
    val message: Message.Assistant,
    val stopReason: StopReason,
    val usage: TokenUsage? = null,
    /** The model the provider says answered, if it says. */
    val model: String? = null,
    /** Why the request was declined, if the provider says: a policy category or the model's own words. */
    val refusal: String? = null,
)

/** One model behind one API; every call the bot makes goes through this. */
interface LlmClient : AutoCloseable {

    suspend fun complete(model: LlmModel, request: ChatRequest): Reply

    override fun close() = Unit
}

/**
 * A provider refused or failed a call: its status and capped body, for `agent/ProviderErrors` to read.
 * [status] is `null` when nothing came back.
 */
class LlmException(
    val provider: String,
    val status: Int?,
    val body: String?,
    cause: Throwable? = null,
    // a stream that ended before its completion event: the one failure a folder raises itself
    val cutShort: Boolean = false,
) : RuntimeException(describe(provider, status, body), cause) {

    private companion object {
        const val BODY_MAX_CHARS = 2_000

        fun describe(provider: String, status: Int?, body: String?): String =
            buildString {
                append(provider)
                append(status?.let { " answered HTTP $it" } ?: " failed")
                body?.trim()?.takeIf { it.isNotEmpty() }?.let { append(": ").append(it.take(BODY_MAX_CHARS)) }
            }
    }
}
