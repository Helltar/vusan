package com.helltar.vusan.config

/**
 * Ambient addressing: answering a group message that calls the bot by name, or follows up on its answer,
 * without a mention, a reply or a command. The key is the whole switch.
 *
 * [names] are the spellings the chat uses besides the bot's own display name; empty means the ones
 * derived from its profile.
 */
data class AddressingConfig(
    val apiKey: String,
    val model: String,
    val names: List<String>,
) {

    init {
        require(apiKey.isNotBlank()) { "OPENAI_ADDRESSING_API_KEY must not be blank" }
        require(model.isNotBlank()) { "OPENAI_ADDRESSING_MODEL must not be blank" }
        require(names.none { it.isBlank() }) { "ADDRESSING_NAMES must not contain a blank name" }
    }

    companion object {
        // measured on 257 group messages: the fewest wrong verdicts of eight models, and no false yes at all
        const val DEFAULT_MODEL = "gpt-5.6-luna"
    }
}
