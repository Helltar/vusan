package com.helltar.vusan.outbox

/** A queued [output] together with the routing decision ([toPrivate]) captured when it was enqueued. */
data class OutboxItem(val output: BotOutput, val toPrivate: Boolean)

class BotOutbox {

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

    fun useDirectMessages() {
        redirectToPrivate = true
    }
}
