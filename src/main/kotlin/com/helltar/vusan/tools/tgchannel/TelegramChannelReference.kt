package com.helltar.vusan.tools.tgchannel

import java.net.URI

internal data class TelegramChannelReference(val username: String) {

    val webPreviewUrl: String get() = "https://t.me/s/$username"

    companion object {
        private val usernameRegex = Regex("""[A-Za-z][A-Za-z0-9_]{4,31}""")

        fun parse(raw: String): TelegramChannelReference {
            val value = raw.trim().removePrefix("@").trim()

            require(value.isNotBlank()) { "Telegram channel must not be blank" }

            val username =
                if (value.startsWith("http://", ignoreCase = true) ||
                    value.startsWith("https://", ignoreCase = true)
                ) {
                    parseUrl(value)
                } else if (value.startsWith("t.me/", ignoreCase = true) ||
                    value.startsWith("telegram.me/", ignoreCase = true)
                ) {
                    parseUrl("https://$value")
                } else {
                    value.substringBefore('/').removePrefix("@")
                }

            require(usernameRegex.matches(username)) {
                "Only public Telegram channel usernames are supported, for example https://t.me/example_channel"
            }

            return TelegramChannelReference(username)
        }

        private fun parseUrl(value: String): String {
            val uri = URI(value)
            val host = uri.host?.lowercase()

            require(host == "t.me" || host == "telegram.me") { "Only t.me or telegram.me links are supported" }

            val segments = uri.path
                .trim('/')
                .split('/')
                .filter { it.isNotBlank() }

            require(segments.isNotEmpty()) { "Telegram channel URL must include a username" }

            require(segments.first() !in setOf("addstickers", "c", "joinchat", "+")) {
                "Only public Telegram channel usernames are supported"
            }

            return if (segments.first() == "s") {
                require(segments.size >= 2) { "Telegram channel URL must include a username after /s/" }
                segments[1]
            } else {
                segments.first()
            }.removePrefix("@")
        }
    }
}
