package com.helltar.vusan.agent.addressing

/** One line of the chat as the classifier reads it: who wrote it and what it says. */
data class ChatLine(val from: String, val text: String)

/**
 * What the classifier is asked about: the new message, the few lines before it, and what code already
 * knows about the bot in this chat.
 *
 * [botNames] lead with the name the bot's own lines go under. [inReplyTo] names the person the message
 * replies to — always a human, since a reply to the bot is answered before anything asks this. [botBusyFor]
 * names the author while the bot is still answering an earlier request of theirs.
 */
data class AddressingInput(
    val botNames: List<String>,
    val recent: List<ChatLine>,
    val message: ChatLine,
    val inReplyTo: String? = null,
    val botBusyFor: String? = null,
)

/** Decides whether a group message nobody tagged the bot in is said to it. */
interface AddressingClassifier {

    /** `null` when the answer held no verdict that could be read. */
    suspend fun isAddressed(input: AddressingInput): Boolean?
}
