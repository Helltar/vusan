package com.helltar.vusan.outbox

/** A queued [output] together with the routing decision ([toPrivate]) captured when it was enqueued. */
data class OutboxItem(val output: BotOutput, val toPrivate: Boolean)

class BotOutbox {

    companion object {
        // upper bound on standalone text bubbles per turn. consecutive sendMessage calls are coalesced
        // into the trailing bubble (see [enqueueText]), so a model that splits one answer into many small
        // messages produces few real sends. this cap still bounds a runaway loop that keeps emitting
        // full-size bubbles, whose blast radius would otherwise flood the chat past Telegram's rate limit.
        const val MAX_TEXT_MESSAGES = 5

        // keep a coalesced bubble within Telegram's 4096-char text limit, with headroom for HTML the model
        // may add. a message that would overflow the trailing bubble starts a new one instead of merging.
        const val MAX_TEXT_MESSAGE_CHARS = 4000

        private const val TEXT_SEPARATOR = "\n\n"
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

    // enqueues a standalone text message, coalescing it into the trailing text bubble while the result
    // fits [MAX_TEXT_MESSAGE_CHARS] so splitting one answer into many small messages stays cheap. a message
    // that cannot merge starts a new bubble; returns false once [MAX_TEXT_MESSAGES] bubbles are queued so
    // the caller can tell the model to stop instead of flooding the chat.
    fun enqueueText(text: String): Boolean {
        val last = items.lastOrNull()

        if (last != null && last.output is BotOutput.Text && last.toPrivate == redirectToPrivate) {
            val merged = last.output.text + TEXT_SEPARATOR + text

            if (merged.length <= MAX_TEXT_MESSAGE_CHARS) {
                items[items.lastIndex] = last.copy(output = BotOutput.Text(merged))
                return true
            }
        }

        if (items.count { it.output is BotOutput.Text } >= MAX_TEXT_MESSAGES)
            return false

        enqueue(BotOutput.Text(text))

        return true
    }

    fun useDirectMessages() {
        redirectToPrivate = true
    }
}
