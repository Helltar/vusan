package com.helltar.vusan.agent

import com.helltar.vusan.agent.conversation.ChatRole
import com.helltar.vusan.agent.conversation.ChatTurn
import com.helltar.vusan.agent.conversation.PromptConversation
import com.helltar.vusan.agent.conversation.toolCallArgsForStorage
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.llm.LlmClient
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.Message
import com.helltar.vusan.llm.Part
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.TokenUsage
import com.helltar.vusan.llm.ToolResult
import com.helltar.vusan.llm.llmJson
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.request.ConversationScope
import com.helltar.vusan.tools.ToolCatalog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class AgentPromptPreparation(
    val toolCatalog: ToolCatalog,
    val systemPrompt: String,
    val tokenBudget: ContextTokenBudget,
)

/**
 * Builds the turns of this deployment: the system prompt, the budget the history has to fit, and an
 * [AgentTurn] over the model client with a conversation's history and tools.
 */
class AgentFactory(
    private val client: LlmClient,
    private val model: LlmModel,
    private val options: RequestOptions = RequestOptions(),
    private val personality: String? = null,
    // the bot's own Telegram handle: inbound sanitizing strips the mention before the prompt is
    // built, so without this the model never sees the name people call it by.
    private val botUsername: String? = null,
    private val botDisplayName: String? = null,
    // model calls one turn may make, the last of them reserved for landing a turn that runs long
    private val maxModelCalls: Int,
    private val contextWindowPolicy: ContextWindowPolicy = ContextWindowPolicy(model),
) {

    init {
        require(maxModelCalls >= MIN_MODEL_CALLS) { "a turn needs at least $MIN_MODEL_CALLS model calls" }
    }

    // the catalog is built before the turn text, not here: what it defers goes into that text as
    // `<tool_groups>`, and the budget below has to weigh the finished prompt.
    fun prepare(toolCatalog: ToolCatalog, currentTurn: String): AgentPromptPreparation {
        val systemPrompt =
            systemPromptFor(personality ?: DEFAULT_PERSONALITY, model.id, botUsername, botDisplayName)

        return AgentPromptPreparation(
            toolCatalog = toolCatalog,
            systemPrompt = systemPrompt,
            tokenBudget =
                contextWindowPolicy.budget(
                    systemPrompt = systemPrompt,
                    currentTurn = currentTurn,
                    tools = toolCatalog.visibleDefinitions(),
                ),
        )
    }

    /** The cache key this conversation's model calls travel under, when the provider takes one: what ties a call in the log to a chat. */
    fun conversationCacheKey(scope: ConversationScope): String? = options.forConversation(scope.toString()).promptCacheKey

    /** The reserve one run may spend on tool results, from which the runner opens its [TurnToolBudget]. */
    val liveToolResultMaxTokens: Int
        get() = contextWindowPolicy.liveToolResultMaxTokens

    fun build(
        scope: ConversationScope,
        conversation: PromptConversation,
        preparation: AgentPromptPreparation,
        outbox: BotOutbox,
        toolBudget: TurnToolBudget,
        toolEvents: (ToolEvent) -> Unit,
        tokenUsage: (TokenUsage) -> Unit,
        onToolStarting: (activity: ToolActivity?) -> Unit = {},
        // a turn nobody called the bot into outright may end without a word; see `owesDelivery`.
        mayStaySilent: Boolean = false,
    ): AgentTurn {
        val history =
            buildList {
                add(Message.System(preparation.systemPrompt))
                // the recap is written from quoted events, so it can carry a delimiter out of them.
                conversation.summary?.let { add(Message.User(xmlBlock("conversation_recap", it.neutralizePromptBlocks()))) }
                addAll(conversation.turns.toMessages())
            }

        // what the request may measure before the turn's own results are folded: the window less what the
        // answer and the estimate's own error are reserved
        val budget = preparation.tokenBudget

        return AgentTurn(
            client = client,
            model = model,
            options = options.forConversation(scope.toString()),
            history = history,
            catalog = preparation.toolCatalog,
            outbox = outbox,
            toolBudget = toolBudget,
            maxModelCalls = maxModelCalls,
            promptTokenCeiling = budget.contextWindowTokens - budget.responseReserveTokens - budget.safetyReserveTokens,
            scope = scope,
            mayStaySilent = mayStaySilent,
            toolEvents = toolEvents,
            tokenUsage = tokenUsage,
            onToolStarting = onToolStarting,
        )
    }

    companion object {
        // a request, a wrap-up and the nudge in between
        const val MIN_MODEL_CALLS = 3
    }
}

/**
 * Stored turns as the messages a request carries. Tool calls of one batch share the assistant message
 * that made them, and their results share the one message that answers them, the way both APIs want it.
 */
internal fun List<ChatTurn>.toMessages(): List<Message> =
    buildList {
        val pendingResults = mutableListOf<ToolResult>()

        fun flushResults() {
            if (pendingResults.isNotEmpty()) add(Message.ToolResults(pendingResults.toList()))
            pendingResults.clear()
        }

        for (turn in this@toMessages) {
            when (turn.role) {
                ChatRole.USER -> {
                    flushResults()
                    add(Message.User(turn.content))
                }

                ChatRole.ASSISTANT -> {
                    flushResults()
                    add(Message.Assistant(turn.content))
                }

                ChatRole.TOOL_CALL -> {
                    val call =
                        Part.ToolCall(
                            id = checkNotNull(turn.toolCallId) { "TOOL_CALL row without toolCallId" },
                            name = checkNotNull(turn.toolName) { "TOOL_CALL row without toolName" },
                            arguments = storedToolArgs(turn.content),
                        )

                    val previous = lastOrNull() as? Message.Assistant

                    if (pendingResults.isEmpty() && previous != null && previous.parts.all { it is Part.ToolCall }) {
                        set(lastIndex, Message.Assistant(previous.parts + call))
                    } else {
                        flushResults()
                        add(Message.Assistant(listOf(call)))
                    }
                }

                ChatRole.TOOL_RESULT ->
                    pendingResults +=
                        ToolResult(
                            callId = checkNotNull(turn.toolCallId) { "TOOL_RESULT row without toolCallId" },
                            name = checkNotNull(turn.toolName) { "TOOL_RESULT row without toolName" },
                            output = turn.content,
                            isError = turn.toolIsError ?: false,
                        )
            }
        }

        flushResults()
    }

// the stored arguments are the json the model sent, bounded; whatever does not read as an object replays as none
private fun storedToolArgs(content: String): JsonObject =
    runCatching { llmJson.parseToJsonElement(toolCallArgsForStorage(content)).jsonObject }.getOrDefault(JsonObject(emptyMap()))
