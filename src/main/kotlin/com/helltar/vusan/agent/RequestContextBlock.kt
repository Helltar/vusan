package com.helltar.vusan.agent

import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.request.RequestContext
import java.time.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * The turn's origin, rendered for the model: where it is answering and who it is answering.
 *
 * [previousExchangeAt] is not part of the request — the runner reads it from history — so it is passed
 * in rather than carried around on a record that ingress fills in.
 */
internal fun RequestContext.toPromptBlock(previousExchangeAt: Instant? = null): String {
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
        }

    return xmlBlock("message_context", lines.joinToString("\n"))
}

// short gaps are ordinary back-and-forth and saying anything about them would be noise, so the line
// only appears once the pause is long enough to be worth noticing.
private val MIN_REPORTED_GAP = 6.hours

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

private fun Long.agoIn(unit: String): String =
    "$this $unit${if (this == 1L) "" else "s"} ago"

// every value here is something a person typed — a display name, a group title, its description —
// and each lands on a line of its own inside the block, so both the line and the block have to
// survive whatever they named themselves.
private fun String.asMetadataValue(maxLength: Int = 160): String? =
    collapseWhitespaceAndCap(maxLength)?.neutralizePromptBlocks()
