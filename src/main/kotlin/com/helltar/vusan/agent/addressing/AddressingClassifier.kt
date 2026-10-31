package com.helltar.vusan.agent.addressing

/** One chat line as the classifier reads it: who wrote it and what it says. */
data class ChatLine(val from: String, val text: String)

/**
 * What the classifier is asked about: the new message, the few lines before it, and what code knows about the
 * bot in this chat.
 *
 * [botNames] lead with the bot's own display name. [inReplyTo] names the person the message replies to,
 * always a human, since a reply to the bot is answered first. [botBusyFor] names the author while the bot
 * still answers an earlier request of theirs.
 */
data class AddressingInput(
    val botNames: List<String>,
    val recent: List<ChatLine>,
    val message: ChatLine,
    val inReplyTo: String? = null,
    val botBusyFor: String? = null,
)

/** Decides whether an untagged group message is said to the bot. */
interface AddressingClassifier {

    /** `null` when the answer held no readable verdict. */
    suspend fun isAddressed(input: AddressingInput): Boolean?
}
