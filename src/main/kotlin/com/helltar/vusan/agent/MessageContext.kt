package com.helltar.vusan.agent

import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.xmlBlock
import java.time.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

data class MessageContext(
    val chatId: Long,
    val chatType: String,
    val isPrivate: Boolean,
    val chatTitle: String? = null,
    val chatUsername: String? = null,
    val chatDescription: String? = null,
    val userId: Long,
    val userDisplayName: String? = null,
    val userUsername: String? = null,
    val userLanguageCode: String? = null,
    val previousExchangeAt: Instant? = null
) {
    fun toPromptBlock(): String {
        val lines =
            buildList {
                add("Chat:")
                add("- id: $chatId")
                add("- type: ${chatType.asMetadataValue() ?: "unknown"}")
                add("- private: $isPrivate")
                chatTitle?.asMetadataValue()?.let { add("- title: $it") }
                chatUsername?.asMetadataValue()?.let { add("- username: $it") }
                chatDescription?.asMetadataValue(maxLength = 700)?.let { add("- description: $it") }
                add("")
                add("Sender:")
                add("- id: $userId")
                userDisplayName?.asMetadataValue()?.let { add("- display_name: $it") }
                userUsername?.asMetadataValue()?.let { add("- username: $it") }
                userLanguageCode?.asMetadataValue()?.let { add("- telegram_language: $it") }
                previousExchangeAt?.let(::elapsedSinceOrNull)?.let { add("- last_exchange: $it") }
            }

        return xmlBlock("message_context", lines.joinToString("\n"))
    }
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

private fun String.asMetadataValue(maxLength: Int = 160): String? =
    collapseWhitespaceAndCap(maxLength)
