package com.helltar.vusan.outbox

/** A queued [output] together with the routing decision ([toPrivate]) captured when it was enqueued. */
data class OutboxItem(val output: BotOutput, val toPrivate: Boolean)

class BotOutbox {

    companion object {
        // upper bound on standalone text messages per turn. a flaky model asked to "send N separate
        // messages" can loop and emit far more, flooding the chat past Telegram's per-chat rate limit
        // (the run then stalls on 429 retries). capping the outbox bounds that blast radius.
        const val MAX_TEXT_MESSAGES = 5
    }

    private val items = mutableListOf<OutboxItem>()

    var redirectToPrivate: Boolean = false
        private set

    val pending: List<OutboxItem>
        get() = items.toList()

    fun enqueue(item: BotOutput) {
        // reactions always target a specific message in the current chat —
        // they must never be routed to the sender's DMs by `useDirectMessages`.
        val toPrivate = redirectToPrivate && item !is BotOutput.Reaction
        items += OutboxItem(item, toPrivate)
    }

    // enqueues a standalone text message, returning false once [MAX_TEXT_MESSAGES] are already queued so
    // the caller can tell the model to stop instead of flooding the chat.
    fun enqueueText(text: String): Boolean {
        if (items.count { it.output is BotOutput.Text } >= MAX_TEXT_MESSAGES) return false
        enqueue(BotOutput.Text(text))
        return true
    }

    fun useDirectMessages() {
        redirectToPrivate = true
    }
}
