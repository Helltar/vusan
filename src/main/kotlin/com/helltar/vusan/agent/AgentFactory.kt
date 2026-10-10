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
    // whether this deployment has a sandbox at all; the prompt says nothing about one where it does not
    private val sandbox: Boolean = false,
    // model calls one turn may make, the last of them reserved for landing a turn that runs long
    private val maxModelCalls: Int,
    private val contextWindowPolicy: ContextWindowPolicy = ContextWindowPolicy(model),
) {

    init {
        require(maxModelCalls >= AppConfig.MIN_MODEL_CALLS) { "a turn needs at least ${AppConfig.MIN_MODEL_CALLS} model calls" }
    }

    // the catalog is built before the turn text, not here: what it defers goes into that text as
    // `<tool_groups>`, and the budget below has to weigh the finished prompt.
    fun prepare(toolCatalog: ToolCatalog, currentTurn: String): AgentPromptPreparation {
        val systemPrompt =
            systemPromptFor(personality ?: DEFAULT_PERSONALITY, model.id, botUsername, botDisplayName, sandbox)

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
        shelf: TurnShelf,
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
            shelf = shelf,
        )
    }

    companion object {
        // a request, a wrap-up and the nudge in between
    }
}
