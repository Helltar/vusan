package com.helltar.vusan.llm

/**
 * A model as this bot needs to know it: what to call it on the wire, how much it holds, and what it
 * can do. Nothing here is a vendor catalog; a deployment names a model, and startup asks the vendor
 * about it where the vendor answers.
 *
 * [efforts] are the ones its vendor lists, where it lists any: the least of them is what a yes-or-no
 * job sends. `null` where nobody said, empty where the vendor said there is nothing to choose.
 */
data class LlmModel(
    val id: String,
    val contextWindowTokens: Long,
    val maxOutputTokens: Int? = null,
    val seesImages: Boolean = true,
    val efforts: Set<ReasoningEffort>? = null,
) {

    init {
        require(id.isNotBlank()) { "a model needs an id" }
        require(contextWindowTokens > 0) { "a model's context window must be positive" }
        require(maxOutputTokens == null || maxOutputTokens > 0) { "a model's output ceiling must be positive" }
    }
}
