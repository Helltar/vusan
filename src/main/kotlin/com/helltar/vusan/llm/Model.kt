package com.helltar.vusan.llm

/**
 * A model as this bot needs to know it: its wire name, how much it holds, and what it can do. No vendor
 * catalog: a deployment names a model, and startup asks the vendor where it answers.
 *
 * [contextWindowTokens] is `null` where nobody stated one (a compatible server whose list is silent), which
 * only the chat model and its fallback cannot do without, since history is planned against them. [efforts]
 * are the ones the vendor lists, if any; the least is what a yes-or-no job sends. `null` where nobody said,
 * empty where there is nothing to choose. [offersToolsMidConversation] is whether it takes tools offered
 * mid-conversation without the tool list changing (Anthropic's `tool_addition`, OpenAI's `additional_tools`);
 * see [Message.ToolsOffered]. [cachesOnlyRepeatedPrefix] is whether its provider keeps no cache breakpoints,
 * so a turn reads history back only where its request repeats the last one item for item; the runner then
 * continues that request instead of replaying the shorter stored history.
 */
data class LlmModel(
    val id: String,
    val contextWindowTokens: Long?,
    val maxOutputTokens: Int? = null,
    val seesImages: Boolean = true,
    val efforts: Set<ReasoningEffort>? = null,
    val offersToolsMidConversation: Boolean = false,
    val cachesOnlyRepeatedPrefix: Boolean = false,
) {

    init {
        require(id.isNotBlank()) { "a model needs an id" }
        require(contextWindowTokens == null || contextWindowTokens > 0) { "a model's context window must be positive" }
        require(maxOutputTokens == null || maxOutputTokens > 0) { "a model's output ceiling must be positive" }
    }
}
