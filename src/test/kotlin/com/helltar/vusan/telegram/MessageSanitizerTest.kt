package com.helltar.vusan.telegram

import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.UserId
import dev.inmo.tgbotapi.types.chat.CommonUser
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.types.message.textsources.TextSource
import dev.inmo.tgbotapi.types.message.textsources.mentionTextSource
import dev.inmo.tgbotapi.types.message.textsources.regularTextSource
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageSanitizerTest {

    private val botUsername = "VusanBot"
    private val botUserId = 4242L

    @Test
    fun `removes leading bot mention`() {
        val content = text("@VusanBot tell me the latest news", mention("VusanBot"), regular(" tell me the latest news"))

        assertEquals("tell me the latest news", sanitizeUserText(content, botUserId, botUsername))
    }

    @Test
    fun `removes bot mention in middle of sentence`() {
        val content = text("hey @VusanBot, check USD to UAH", regular("hey "), mention("VusanBot"), regular(", check USD to UAH"))

        assertEquals("hey, check USD to UAH", sanitizeUserText(content, botUserId, botUsername))
    }

    @Test
    fun `keeps other mentions intact`() {
        val content = text(
            "@someone ask @VusanBot about the news",
            mention("someone"),
            regular(" ask "),
            mention("VusanBot"),
            regular(" about the news")
        )

        assertEquals("@someone ask about the news", sanitizeUserText(content, botUserId, botUsername))
    }

    @Test
    fun `still strips text_mention when bot username is unknown`() {
        val content = text("Vusan, ping", textMention("Vusan", botUserId), regular(", ping"))

        assertEquals("ping", sanitizeUserText(content, botUserId, botUsername = null))
    }

    @Test
    fun `removes leading bot text_mention`() {
        val content = text("Vusan, how do I make a checkbox?", textMention("Vusan", botUserId), regular(", how do I make a checkbox?"))

        assertEquals("how do I make a checkbox?", sanitizeUserText(content, botUserId, botUsername))
    }

    @Test
    fun `keeps text_mention pointing at someone else`() {
        val content = text("Bob, ping", textMention("Bob", botUserId + 1), regular(", ping"))

        assertEquals("Bob, ping", sanitizeUserText(content, botUserId, botUsername))
    }

    @Test
    fun `mention only becomes blank after sanitization`() {
        val content = text("@VusanBot", mention("VusanBot"))

        assertEquals("", sanitizeUserText(content, botUserId, botUsername))
    }

    private fun text(rawText: String, vararg sources: TextSource): TextContent =
        TextContent(text = rawText, textSources = sources.toList())

    private fun mention(username: String): TextSource =
        mentionTextSource(username)

    private fun textMention(text: String, userId: Long): TextSource =
        mentionTextSource(text, CommonUser(UserId(RawChatId(userId)), firstName = "Vusan"))

    private fun regular(text: String): TextSource =
        regularTextSource(text)
}
