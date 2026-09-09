package com.helltar.vusan.delivery

import com.helltar.vusan.agent.AgentResult
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.request.UserRef

/**
 * What a turn's answer is sent with, and everything the application knows about sending: an
 * adapter implements it, and nothing outside one needs a messenger client to deliver a turn.
 */
interface OutputDelivery {

    /** Sends everything a finished turn produced. */
    suspend fun deliver(delivery: TurnDelivery): DeliveryOutcome

    /** Sends one line of plain text from the bot itself — no anchor, no formatting fallbacks. */
    suspend fun notify(destination: Destination, text: String): DeliveryOutcome
}

/**
 * One finished turn, addressed.
 *
 * [recipient] is the person, not a private destination: which chat that resolves to — if the platform
 * even has one — is the adapter's to work out, and an item is only routed there when the turn asked.
 */
data class TurnDelivery(
    val result: AgentResult,
    val destination: Destination,
    val recipient: UserRef,
    val language: Language,
    val attribution: Attribution? = null
)

/**
 * What became of a send.
 *
 * There is deliberately no `Partial`: an adapter cannot honestly report one today, because an item
 * refused for its own content is retried through a fallback chain and can still land in some other
 * shape. What the caller genuinely needs to tell apart is a destination that is *gone* — every later
 * send there fails too, so a scheduler must stop firing into it — from everything else, which says
 * nothing about whether the next message would arrive.
 *
 * Do not add a state an adapter cannot actually distinguish; the caller would branch on a lie.
 */
sealed interface DeliveryOutcome {

    /** Delivery ran. Individual items may still have been refused for their own content. */
    data object Handled : DeliveryOutcome

    /** The destination is gone — the bot was removed, blocked, or lost its right to post. */
    data object Unreachable : DeliveryOutcome

    val isUnreachable: Boolean
        get() = this is Unreachable
}
