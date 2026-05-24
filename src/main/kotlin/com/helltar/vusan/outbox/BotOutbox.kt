package com.helltar.vusan.outbox

class BotOutbox {

    private val items = mutableListOf<BotOutput>()

    var redirectToPrivate: Boolean = false
        private set

    val pending: List<BotOutput>
        get() = items.toList()

    fun enqueue(item: BotOutput) {
        // Reactions always target a specific message in the current chat —
        // they must never be routed to the sender's DMs by `useDirectMessages`.
        if (item !is BotOutput.Reaction) item.toPrivate = redirectToPrivate
        items += item
    }

    fun useDirectMessages() {
        redirectToPrivate = true
    }
}
