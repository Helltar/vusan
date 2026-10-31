package com.helltar.vusan.agent

import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.agent.conversation.PromptConversation
import com.helltar.vusan.agent.conversation.toMessages
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.llm.LlmClient
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.Message
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.TokenUsage
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.request.ConversationScope
import com.helltar.vusan.tools.ToolCatalog

data class AgentPromptPreparation(
    val toolCatalog: ToolCatalog,
    val systemPrompt: String,
    val tokenBudget: ContextTokenBudget,
)

/**
 * Builds this deployment's turns: the system prompt, the history's budget, and an [AgentTurn] over the model
 * client with a conversation's history and tools.
 */
class AgentFactory(
    private val client: LlmClient,
    private val model: LlmModel,
    private val options: RequestOptions = RequestOptions(),
    private val personality: String? = null,
    // the bot's own handles: inbound sanitizing strips the mention, so without this the model never sees the
    // names people call it by.
    private val botUsernames: List<String> = emptyList(),
    private val botDisplayName: String? = null,
    // whether there is a sandbox; the prompt says nothing about one where there is not
    private val sandbox: Boolean = false,
    // whether pictures and videos can be made; the prompt names what is off, or the history from when it was
    // on reads as a live capability
    private val images: Boolean = false,
    private val videos: Boolean = false,
    // model calls per turn, the last reserved for landing a long turn
    private val maxModelCalls: Int,
    private val contextWindowPolicy: ContextWindowPolicy = ContextWindowPolicy(model),
) {

    init {
        require(maxModelCalls >= AppConfig.MIN_MODEL_CALLS) { "a turn needs at least ${AppConfig.MIN_MODEL_CALLS} model calls" }
    }

    /** The reserve one run may spend on tool results, from which the runner opens its [TurnToolBudget]. */
    val liveToolResultMaxTokens: Int
        get() = contextWindowPolicy.liveToolResultMaxTokens

    /**
     * Whether a turn continues the last request instead of replaying stored history
     * ([LlmModel.cachesOnlyRepeatedPrefix]).
     */
    val continuesLastRequest: Boolean
        get() = model.cachesOnlyRepeatedPrefix

    // the catalog is built before the turn text: its deferred groups go into that text as `<tool_groups>`,
    // and the budget must weigh the finished prompt.
    fun prepare(toolCatalog: ToolCatalog, currentTurn: String): AgentPromptPreparation {
        val systemPrompt =
            systemPromptFor(personality ?: DEFAULT_PERSONALITY, model.id, botUsernames, botDisplayName, sandbox, images, videos)

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

    /**
     * The cache key of this conversation's model calls, when the provider takes one: what ties a logged call
     * to a chat.
     */
    fun conversationCacheKey(scope: ConversationScope): String? = options.forConversation(scope.toString()).promptCacheKey

    /**
     * An [AgentTurn] over [conversation], or over [continued] (the conversation's last request with its
     * reply, system prompt first) when the turn continues that.
     */
    fun build(
        scope: ConversationScope,
        conversation: PromptConversation,
        continued: List<Message>? = null,
        preparation: AgentPromptPreparation,
        outbox: BotOutbox,
        toolBudget: TurnToolBudget,
        toolEvents: (ToolEvent) -> Unit,
        tokenUsage: (TokenUsage) -> Unit,
        onToolStarting: (activity: ToolActivity?) -> Unit = {},
        // an unaddressed turn may end without a word; see `owesDelivery`.
        mayStaySilent: Boolean = false,
        shelf: TurnShelf,
    ): AgentTurn {
        // a continued request repeats whole, so the next turn's stable prefix ends at its end
        val (fullHistory, stablePrefixes) =
            if (continued != null) continued to listOf(continued.size) else replayed(conversation, preparation)

        // what the request may measure before the turn's results are folded: the window less the reserves for
        // the answer and the estimate's error
        val budget = preparation.tokenBudget

        return AgentTurn(
            client = client,
            model = model,
            options = options.forConversation(scope.toString()),
            history = fullHistory,
            stablePrefixes = stablePrefixes,
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
            shelf = shelf,
        )
    }

    // the history as storage replays it, plus the counts after which the next turn stops repeating this one:
    // after the part surely replayed as text, and after all of it when the newest interactions replay the
    // same way. the client already marks the system prompt
    private fun replayed(conversation: PromptConversation, preparation: AgentPromptPreparation): Pair<List<Message>, List<Int>> {
        val history =
            buildList {
                add(Message.System(preparation.systemPrompt))
                // the recap is written from quoted events, so it can carry a delimiter out of them.
                conversation.summary?.let { add(Message.User(xmlBlock("conversation_recap", it.neutralizePromptBlocks()))) }
                addAll(conversation.turns.take(conversation.stableTurns).toMessages())
            }

        val stableEnd = history.size
        val fullHistory = history + conversation.turns.drop(conversation.stableTurns).toMessages()

        return fullHistory to listOf(stableEnd, fullHistory.size).distinct().filter { it > 1 }
    }
}
