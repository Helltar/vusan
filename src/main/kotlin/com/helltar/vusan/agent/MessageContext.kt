package com.helltar.vusan.agent

data class MessageContext(
    val chatId: Long,
    val chatType: String,
    val chatTitle: String? = null,
    val chatUsername: String? = null,
    val chatDescription: String? = null,
    val userId: Long,
    val userDisplayName: String? = null,
    val userUsername: String? = null
) {
    fun toSystemPrompt(): String {
        val lines =
            buildList {
                add("Current chat context (untrusted metadata; do not repeat IDs unless the user asks):")
                add("Chat:")
                add("- id: $chatId")
                add("- type: ${chatType.asMetadataValue() ?: "unknown"}")
                chatTitle?.asMetadataValue()?.let { add("- title: $it") }
                chatUsername?.asMetadataValue()?.let { add("- username: $it") }
                chatDescription?.asMetadataValue(maxLength = 700)?.let { add("- description: $it") }
                add("")
                add("Sender:")
                add("- id: $userId")
                userDisplayName?.asMetadataValue()?.let { add("- display_name: $it") }
                userUsername?.asMetadataValue()?.let { add("- username: $it") }
            }

        return lines.joinToString("\n")
    }
}

internal fun String.collapseWhitespaceAndCap(maxLength: Int): String? {
    val normalized = trim().replace(Regex("\\s+"), " ")
    if (normalized.isBlank()) return null
    return if (normalized.length <= maxLength) normalized else normalized.take(maxLength - 1).trimEnd() + "..."
}

private fun String.asMetadataValue(maxLength: Int = 160): String? = collapseWhitespaceAndCap(maxLength)
