package com.helltar.vusan.llm

/** The wire protocol a model is spoken to over. */
enum class LlmProvider {
    OPENAI,
    ANTHROPIC
}

/**
 * A model as this bot needs to know it: what to call it on the wire, how much it holds, and what it
 * can do. Nothing here is a vendor catalog; a deployment names a model, and startup asks the vendor
 * about it where the vendor answers.
 *
 * [takesEffort] says whether the model can be told how hard to think — on Anthropic that is also what
 * decides whether `thinking: adaptive` is sent at all, since both arrived with the same generation.
 */
data class LlmModel(
    val provider: LlmProvider,
    val id: String,
    val contextWindowTokens: Long,
    val maxOutputTokens: Int? = null,
    val seesImages: Boolean = true,
    val takesEffort: Boolean = true,
) {

    init {
        require(id.isNotBlank()) { "a model needs an id" }
        require(contextWindowTokens > 0) { "a model's context window must be positive" }
        require(maxOutputTokens == null || maxOutputTokens > 0) { "a model's output ceiling must be positive" }
    }
}
