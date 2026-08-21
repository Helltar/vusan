package com.helltar.vusan.tools.tgchannel

import io.ktor.http.*
import java.net.URI

internal data class TelegramChannelReference(val username: String) {

    /**
     * The public web preview, optionally one batch further back ([before], the id Telegram's own
     * "load more" link points at) and optionally restricted to an in-channel search ([query]).
     */
    fun webPreviewUrl(before: Long? = null, query: String = ""): String =
        buildString {
            append("https://t.me/s/")
            append(username)

            val params =
                buildList {
                    query.trim().takeIf { it.isNotEmpty() }?.let { add("q=${it.encodeURLParameter()}") }
                    before?.let { add("before=$it") }
                }

            if (params.isNotEmpty()) params.joinTo(this, separator = "&", prefix = "?")
        }

    companion object {
        private val usernameRegex = Regex("""[A-Za-z][A-Za-z0-9_]{4,31}""")
        private val unsupportedFirstSegments = setOf("addstickers", "c", "joinchat", "+")

        fun parse(raw: String): TelegramChannelReference {
            val value = raw.trim().removePrefix("@").trim()

            require(value.isNotBlank()) { "Telegram channel must not be blank" }

            val username =
                when {
                    value.startsWith("http://", ignoreCase = true) ||
                            value.startsWith("https://", ignoreCase = true) -> parseUrl(value)

                    value.startsWith("t.me/", ignoreCase = true) ||
                            value.startsWith("telegram.me/", ignoreCase = true) -> parseUrl("https://$value")

                    else -> value.substringBefore('/').removePrefix("@")
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

            require(segments.first() !in unsupportedFirstSegments) {
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
