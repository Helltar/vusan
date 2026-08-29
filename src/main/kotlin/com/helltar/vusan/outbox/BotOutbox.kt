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

        // telegram's album size. an eleventh track starts a second album rather than being dropped.
        private const val MAX_MEDIA_GROUP = 10
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

        if (item is BotOutput.Audio && mergeIntoTrailingAlbum(item, toPrivate)) return

        items += OutboxItem(item, toPrivate)
    }

    // a track is fetched one tool call at a time, so seven of them would otherwise arrive as seven
    // messages, each repeating the reply quote. consecutive ones become one album instead — the
    // moment anything else is queued between them, the run of tracks is over and a new album starts.
    private fun mergeIntoTrailingAlbum(audio: BotOutput.Audio, toPrivate: Boolean): Boolean {
        val last = items.lastOrNull()?.takeIf { it.toPrivate == toPrivate } ?: return false

        val album =
            when (val output = last.output) {
                is BotOutput.Audio -> listOf(output, audio)
                is BotOutput.AudioGroup -> output.audios.takeIf { it.size < MAX_MEDIA_GROUP }?.plus(audio)
                else -> null
            } ?: return false

        items[items.lastIndex] = last.copy(output = BotOutput.AudioGroup(album))

        return true
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

        if (standaloneBubbleCount() >= MAX_TEXT_MESSAGES)
            return false

        enqueue(BotOutput.Text(text))

        return true
    }

    // opt-in rich messages never coalesce — each is a deliberate structured send — but they share the
    // standalone bubble budget with plain text so a runaway turn cannot flood the chat.
    fun enqueueRichMessage(markdown: String): Boolean {
        if (standaloneBubbleCount() >= MAX_TEXT_MESSAGES)
            return false

        enqueue(BotOutput.RichMessage(markdown))

        return true
    }

    fun enqueueInlineChoice(choice: BotOutput.InlineChoice): Boolean {
        if (standaloneBubbleCount() >= MAX_TEXT_MESSAGES)
            return false

        enqueue(choice)

        return true
    }

    private fun standaloneBubbleCount(): Int =
        items.count {
            it.output is BotOutput.Text ||
                    it.output is BotOutput.RichMessage ||
                    it.output is BotOutput.InlineChoice
        }

    fun useDirectMessages() {
        redirectToPrivate = true
    }
}
