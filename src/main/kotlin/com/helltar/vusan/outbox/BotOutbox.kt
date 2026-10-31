package com.helltar.vusan.outbox

import com.helltar.vusan.request.ChatCapabilities
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * A queued [output] with the routing decision ([toPrivate]) made at enqueue. [delivered] marks one already in
 * the chat mid-turn: delivery records it without sending it again. [announcement] marks the plan a turn
 * promised before working: sent like anything else, but not an answer.
 */
data class OutboxItem(
    val output: BotOutput,
    val toPrivate: Boolean,
    val delivered: Boolean = false,
    val announcement: Boolean = false,
)

/**
 * The queue a turn writes its answer into.
 *
 * [capabilities] is what the destination accepts. A refused output is dropped here, before its download,
 * generation or synthesis is paid for, and [enqueue] says so: a tool reporting "sent" for something nobody
 * will see is worse than one saying it could not.
 *
 * Read-only calls of a batch run in parallel and may still read the queue, so every method synchronizes on
 * one monitor.
 */
class BotOutbox(val capabilities: ChatCapabilities = ChatCapabilities.UNRESTRICTED) {

    private val items = mutableListOf<OutboxItem>()

    var redirectToPrivate: Boolean = false
        private set

    /** Why the turn answers with nothing, once [staySilent] said so; `null` until then. */
    @get:Synchronized
    var silence: String? = null
        private set

    @get:Synchronized
    val pending: List<OutboxItem>
        get() = items.toList()

    /** Whether the turn already put something in the chat itself. */
    @get:Synchronized
    val hasDelivered: Boolean
        get() = items.any { it.delivered }

    /** Whether the plan was announced; a second announcement would rewrite what the user read. */
    @get:Synchronized
    val hasAnnounced: Boolean
        get() = items.any { it.delivered && it.announcement }

    /**
     * Whether anything is waiting to be sent. An announcement does not count: a turn that made one and queued
     * nothing else still owes everything it promised.
     */
    @get:Synchronized
    val hasQueuedOutput: Boolean
        get() = items.any { !it.delivered && !it.announcement }

    /** Whether the turn has an answer at all, queued or already in the chat. */
    @get:Synchronized
    val hasAnswered: Boolean
        get() = items.any { !it.announcement }

    /** Queues [item], or answers `false` when this chat does not accept that kind of output. */
    @Synchronized
    fun enqueue(item: BotOutput): Boolean {
        if (!capabilities.allows(item)) {
            log.info { "dropped a queued ${item.kindName()} output: this chat does not accept them" }
            return false
        }

        // reactions target a message in the current chat and are never redirected to DMs.
        val toPrivate = redirectToPrivate && item !is BotOutput.Reaction

        if (item is BotOutput.Audio && mergeIntoTrailingAlbum(item, toPrivate)) return true

        items += OutboxItem(item, toPrivate)

        return true
    }

    // tracks arrive one tool call at a time, so consecutive ones become one album instead of separate
    // messages each repeating the reply quote; anything queued between them starts a new album.
    private fun mergeIntoTrailingAlbum(audio: BotOutput.Audio, toPrivate: Boolean): Boolean {
        val last = items.lastOrNull()?.takeIf { it.toPrivate == toPrivate && !it.delivered } ?: return false

        val album =
            when (val output = last.output) {
                is BotOutput.Audio -> listOf(output, audio)
                is BotOutput.AudioGroup -> output.audios.takeIf { it.size < MAX_MEDIA_GROUP }?.plus(audio)
                else -> null
            } ?: return false

        items[items.lastIndex] = last.copy(output = BotOutput.AudioGroup(album))

        return true
    }

    // coalesces into the trailing text bubble while it fits [MAX_TEXT_MESSAGE_CHARS], so an answer split into
    // small messages costs few sends; returns false once [MAX_TEXT_MESSAGES] bubbles are queued, so the model
    // can be told to stop.
    @Synchronized
    fun enqueueText(text: String, announcement: Boolean = false): Boolean {
        val last = items.lastOrNull()?.takeIf { it.toPrivate == redirectToPrivate && !it.delivered }

        if (last != null && last.output is BotOutput.Text) {
            val merged = last.output.text + TEXT_SEPARATOR + text

            if (merged.length <= MAX_TEXT_MESSAGE_CHARS) {
                // a bubble stops being a mere promise once a real message joins it.
                items[items.lastIndex] =
                    last.copy(output = BotOutput.Text(merged), announcement = last.announcement && announcement)
                return true
            }
        }

        if (standaloneBubbleCount() >= MAX_TEXT_MESSAGES)
            return false

        items += OutboxItem(BotOutput.Text(text), toPrivate = redirectToPrivate, announcement = announcement)

        return true
    }

    // records text the turn already sent, so it reaches history and the transcript like a queued message.
    // never refused or redirected: it is already out, and refusing would only lose it from history. an
    // announcement is a promise; anything else is an answer.
    @Synchronized
    fun recordDelivered(text: String, announcement: Boolean = true) {
        items += OutboxItem(BotOutput.Text(text), toPrivate = false, delivered = true, announcement = announcement)
    }

    // rich messages never coalesce, but share the bubble budget with plain text so a runaway turn cannot
    // flood the chat.
    @Synchronized
    fun enqueueRichMessage(markdown: String): Boolean {
        if (standaloneBubbleCount() >= MAX_TEXT_MESSAGES)
            return false

        enqueue(BotOutput.RichMessage(markdown))

        return true
    }

    @Synchronized
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

    @Synchronized
    fun useDirectMessages() {
        redirectToPrivate = true
    }

    /**
     * Declares that this turn answers with nothing on purpose, for [reason], a phrase for the log only.
     * Refused once anything is queued or in the chat, an announcement included: silence is an answer only
     * where there is no other, and a promise followed by nothing is a promise broken.
     */
    @Synchronized
    fun staySilent(reason: String): Boolean {
        if (items.isNotEmpty()) return false

        silence = reason

        return true
    }

    companion object {
        private val log = KotlinLogging.logger {}

        // bubbles per turn. coalescing keeps a split answer to few sends; this cap bounds a runaway loop
        // emitting full-size bubbles past telegram's rate limit.
        const val MAX_TEXT_MESSAGES = 5

        // under telegram's 4096-char limit, with headroom for HTML.
        const val MAX_TEXT_MESSAGE_CHARS = 4000

        private const val TEXT_SEPARATOR = "\n\n"

        // telegram's album size; an eleventh track starts a second album.
        private const val MAX_MEDIA_GROUP = 10
    }
}
