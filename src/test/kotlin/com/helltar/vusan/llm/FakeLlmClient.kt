package com.helltar.vusan.llm

internal val TEST_MODEL = LlmModel("test", contextWindowTokens = 16_384)

/**
 * A client that answers every call with the same text and remembers what it was asked, for the callers
 * that build one prompt and read one answer: vision, the recap, the addressing verdict.
 */
internal class FakeLlmClient(private val response: String = "description") : LlmClient {

    var callCount = 0
        private set

    var lastRequest: ChatRequest? = null
        private set

    val promptText: String
        get() =
            lastRequest?.messages.orEmpty().joinToString("\n") { message ->
                when (message) {
                    is Message.System -> message.text
                    is Message.User -> message.text
                    is Message.Assistant -> message.text
                    is Message.ToolResults -> message.results.joinToString("\n") { it.output }
                }
            }

    val attachmentCount: Int
        get() = lastRequest?.messages.orEmpty().filterIsInstance<Message.User>().sumOf { user -> user.parts.count { it is Part.Image } }

    override suspend fun complete(model: LlmModel, request: ChatRequest): Reply {
        callCount++
        lastRequest = request

        return Reply(Message.Assistant(response), StopReason.END)
    }
}

/**
 * A client that answers calls from a script, one reply per call, and remembers every request — for the
 * agent loop, whose tool rounds are what the script shapes.
 */
internal class ScriptedLlmClient(private vararg val replies: Reply) : LlmClient {

    val requests = mutableListOf<ChatRequest>()
    var failWith: Throwable? = null

    override suspend fun complete(model: LlmModel, request: ChatRequest): Reply {
        requests += request
        failWith?.let { throw it }

        return replies.getOrNull(requests.size - 1) ?: error("the script has no reply for call ${requests.size}")
    }
}

internal fun textReply(text: String) = Reply(Message.Assistant(text), StopReason.END)

internal fun toolCallReply(vararg calls: Part.ToolCall) = Reply(Message.Assistant(calls.toList()), StopReason.TOOL_CALLS)
