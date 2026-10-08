package com.helltar.vusan.llm

import kotlinx.serialization.json.JsonObject

/**
 * How hard a reasoning model thinks before it answers, named the way the APIs spell it.
 *
 * Which of these a model accepts is up to the model: OpenAI takes `none` to `max`, Anthropic `low` to
 * `max`, and either refuses the rest with a 400 — so the resolvers check what they can at startup.
 */
enum class ReasoningEffort {
    NONE,
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX;

    /** The value a request carries, which is also how the Codex model catalog lists it. */
    val requestValue: String
        get() = name.lowercase()
}

/**
 * What a request asks of the model beyond its messages. One shape for every provider: a client reads
 * the fields its API has and leaves the rest alone.
 *
 * [cachePrompt] asks the provider to keep the prompt's prefix for the next call — Anthropic's
 * `cache_control` and OpenAI's explicit breakpoints; off for a prompt whose body never repeats, since
 * the write would buy a read nobody makes. [promptCacheKey] is OpenAI's: it routes a conversation's
 * requests to the machine holding its prefix, and travels only to servers that know it.
 */
data class RequestOptions(
    val reasoningEffort: ReasoningEffort? = null,
    val verbosity: String? = null,
    val serviceTier: String? = null,
    val promptCacheKey: String? = null,
    val parallelToolCalls: Boolean? = null,
    val maxOutputTokens: Int? = null,
    val cachePrompt: Boolean = true,
) {

    /**
     * The same options with a cache key of this conversation's own.
     *
     * Reads match the most recently written prefixes under a key, so one key for the whole deployment
     * means busy chats evict each other; a key per conversation gives each its own window. The
     * conversation is hashed rather than named: the key only routes a request, a collision costs
     * nothing because a read still needs an exact prefix match, and a messenger's user id has no
     * business travelling to a provider. Options without a key keep none.
     */
    fun forConversation(conversation: String): RequestOptions =
        promptCacheKey?.let { copy(promptCacheKey = "$it-${conversation.hashCode().toUInt().toString(HEX_RADIX)}") } ?: this

    private companion object {
        const val HEX_RADIX = 16
    }
}

/** A tool as the model is told about it: its name, what it is for, and the JSON schema of its arguments. */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

data class ChatRequest(
    val messages: List<Message>,
    val tools: List<ToolDefinition> = emptyList(),
    val options: RequestOptions = RequestOptions(),
) {

    init {
        require(messages.isNotEmpty()) { "a request carries at least one message" }
    }
}

enum class StopReason {
    /** The model finished its answer. */
    END,

    /** The model wants its tool calls answered. */
    TOOL_CALLS,

    /** The output ceiling cut the answer short, a tool call's arguments as much as its text. */
    MAX_TOKENS,

    /** The model or a safety classifier declined the request; a retry of the same prompt is declined again. */
    REFUSAL,

    OTHER
}

data class TokenUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    // what the provider served from its prompt cache and what it wrote to it, where it says so
    val cacheReadTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
) {

    val totalTokens: Int?
        get() = if (inputTokens == null && outputTokens == null) null else (inputTokens ?: 0) + (outputTokens ?: 0)
}

data class Reply(
    val message: Message.Assistant,
    val stopReason: StopReason,
    val usage: TokenUsage? = null,
    /** The model the provider says answered, when it says. */
    val model: String? = null,
    /** Why the request was declined, when the provider says: a policy category, or the model's own words. */
    val refusal: String? = null,
)

/** One model behind one API. Every call the bot makes goes through this. */
interface LlmClient : AutoCloseable {

    suspend fun complete(model: LlmModel, request: ChatRequest): Reply

    override fun close() = Unit
}

/**
 * A provider refused or failed a call: the status it answered with, and its body, capped, for the
 * rules in `agent/ProviderErrors` to read. [status] is `null` when nothing came back at all.
 */
class LlmException(
    val provider: String,
    val status: Int?,
    val body: String?,
    cause: Throwable? = null,
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
