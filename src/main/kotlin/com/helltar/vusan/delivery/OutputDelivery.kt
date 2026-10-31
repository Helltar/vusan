package com.helltar.vusan.delivery

import com.helltar.vusan.agent.AgentResult
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.UserRef

/** How a turn's answer is sent: an adapter implements it, so nothing outside one needs a messenger client. */
interface OutputDelivery {

    /** Sends everything a finished turn produced. */
    suspend fun deliver(delivery: TurnDelivery): DeliveryOutcome

    /**
     * Sends what the bot says on its own: no person to route to and no attribution line. [anchorMessageId] is
     * the message it picks up, if any; the outputs stand alone when that message is gone.
     */
    suspend fun deliverUnprompted(
        destination: Destination,
        outputs: List<BotOutput>,
        anchorMessageId: String? = null,
    ): DeliveryOutcome

    /** Sends one line of plain text from the bot: no anchor, no formatting fallbacks. */
    suspend fun notify(destination: Destination, text: String): DeliveryOutcome
}

/**
 * One finished turn, addressed. [recipient] is the person, not a private destination: the adapter works out
 * which chat that is, if any, and routes an item there only when the turn asked.
 */
data class TurnDelivery(
    val result: AgentResult,
    val destination: Destination,
    val recipient: UserRef,
    val language: Language,
    val attribution: Attribution? = null,
)

/**
 * What became of a send.
 *
 * No `Partial` on purpose: an item refused for its content goes through a fallback chain and may still land
 * in another shape, so no adapter can honestly report one. What the caller needs is a destination that is
 * *gone* (every later send fails, so a scheduler must stop) versus everything else. Do not add a state an
 * adapter cannot actually distinguish.
 */
sealed interface DeliveryOutcome {

    /** Delivery ran; individual items may still have been refused for their content. */
    data object Handled : DeliveryOutcome

    /** The destination is gone: the bot was removed, blocked or lost its right to post. */
    data object Unreachable : DeliveryOutcome

    val isUnreachable: Boolean
        get() = this is Unreachable
}
