package com.helltar.vusan.agent

import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.request.RequestContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * The turn's origin rendered for the model: where it answers and whom.
 *
 * [previousExchangeAt] comes from history, not the request, so it is passed in rather than carried on the
 * ingress record; the same goes for [lastAnswerOfStretch], the [ChatFloor]'s verdict at admission, and
 * [leftAloneUntil], the end of the sender's leave-alone request.
 */
internal fun RequestContext.toPromptBlock(
    previousExchangeAt: Instant? = null,
    lastAnswerOfStretch: Boolean = false,
    leftAloneUntil: Instant? = null,
): String {
    val lines =
        buildList {
            add("Chat:")
            add("- id: ${chat.id}")
            add("- type: ${chat.type.asMetadataValue() ?: "unknown"}")
            add("- private: ${chat.isPrivate}")
            chat.title?.asMetadataValue()?.let { add("- title: $it") }
            chat.username?.asMetadataValue()?.let { add("- username: $it") }
            chat.description?.asMetadataValue(maxLength = 700)?.let { add("- description: $it") }
            chat.capabilities.restrictedKinds
                .takeIf { it.isNotEmpty() }
                ?.let { add("- this chat does not accept: ${it.joinToString(", ")}") }

            chat.capabilities.slowModeSeconds
                .takeIf { it > 0 }
                ?.let { add("- slow mode: one message every ${it}s, so answer in a single message") }

            add("")
            add("Sender:")
            add("- id: ${sender.id}")
            sender.displayName?.asMetadataValue()?.let { add("- display_name: $it") }
            sender.username?.asMetadataValue()?.let { add("- username: $it") }
            sender.languageCode?.asMetadataValue()?.let { add("- client_language: $it") }
            previousExchangeAt?.let(::elapsedSinceOrNull)?.let { add("- last_exchange: $it") }

            // history alone puts the message after that reply, the one order it was not written in.
            if (writtenBefore(previousExchangeAt)) {
                add("")
                add("Timing:")
                add(
                    "- before_your_last_reply: the sender wrote this while you were still working on the last " +
                            "exchange in your history, and your reply reached the chat only after it, so they had " +
                            "not seen that reply yet",
                )
            }

            // worded as the guess it is: told the message "was judged to be meant for you", claude and
            // deepseek answered a plain reply to someone else every time (2026-10-27); this wording let them
            // leave it
            if (ambient) {
                add("")
                add("Addressing:")
                add(
                    "- implicit: nobody mentioned you, replied to you or used a command; a quick classifier guessed this " +
                            "message is meant for you, and it is often wrong: the chat, a reply to someone else, or a question " +
                            "somebody else was asked can show it is not yours",
                )
            }

            leftAloneUntil?.let { until ->
                add("")
                add("Left alone:")
                add(
                    "- asked_to_be_left_alone: the sender asked you not to write to them unprompted in this chat, " +
                            "and that holds until ${leftAloneEnd(until)}. Answer what they put to you themselves, " +
                            "keep to it and offer nothing beyond it; anything you would send them unasked — a " +
                            "follow-up, a line of your own — waits until then",
                )
            }

            if (lastAnswerOfStretch) {
                add("")
                add("Floor:")
                add(
                    "- last_answer_of_stretch: you have been answering this sender alone for a while and nobody " +
                            "else has written in the chat, so this is your last reply to them until somebody else " +
                            "writes here or ${ChatFloor.COOLING_PERIOD.inWholeMinutes} minutes pass; their messages " +
                            "until then go unanswered. Answer what they asked, round the exchange off in a sentence " +
                            "in your own voice and say you will pick it up later; start nothing new",
                )
            }
        }

    return xmlBlock("message_context", lines.joinToString("\n"))
}

// short gaps are ordinary back-and-forth; the line appears only once a pause is worth noticing.
private val MIN_REPORTED_GAP = 6.hours

// on `<current_time>`'s clock, so the model can tell how far off the end is
private val LEFT_ALONE_END = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun leftAloneEnd(until: Instant): String {
    val zone = ZoneId.systemDefault()

    return "${LEFT_ALONE_END.format(until.atZone(zone))} (${zone.id})"
}

private fun elapsedSinceOrNull(previous: Instant): String? {
    val gap = (Instant.now().toEpochMilli() - previous.toEpochMilli()).milliseconds
    if (gap < MIN_REPORTED_GAP) return null

    return when {
        gap < 1.days -> gap.inWholeHours.agoIn("hour")
        gap < 30.days -> gap.inWholeDays.agoIn("day")
        gap < 365.days -> (gap.inWholeDays / 30).agoIn("month")
        else -> "over a year ago"
    }
}

// the previous exchange is stored when its turn ends, so a message written before that waited behind it.
// dates may be whole seconds, so a message within a second after the reply counts too: its author could not
// have read it either.
private fun RequestContext.writtenBefore(previousExchangeAt: Instant?): Boolean =
    writtenAt != null && previousExchangeAt != null && previousExchangeAt.isAfter(writtenAt)

private fun Long.agoIn(unit: String): String =
    "$this $unit${if (this == 1L) "" else "s"} ago"

// every value is user text (a display name, a group title, a description) on its own line inside the block,
// so both must survive whatever people named themselves.
private fun String.asMetadataValue(maxLength: Int = 160): String? =
    collapseWhitespaceAndCap(maxLength)?.neutralizePromptBlocks()
