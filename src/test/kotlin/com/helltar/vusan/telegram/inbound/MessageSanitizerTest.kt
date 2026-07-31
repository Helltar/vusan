package com.helltar.vusan.telegram.inbound

import kotlin.test.Test
import kotlin.test.assertEquals
import org.telegram.telegrambots.meta.api.objects.EntityType
import org.telegram.telegrambots.meta.api.objects.MessageEntity
import org.telegram.telegrambots.meta.api.objects.User

class MessageSanitizerTest {

    private val botUsername = "VusanBot"
    private val botUserId = 4242L

    @Test
    fun `removes leading bot mention`() {
        val content = text("@VusanBot tell me the latest news", mention("VusanBot", offset = 0))

        assertEquals("tell me the latest news", sanitizeUserText(content, botUserId, botUsername))
    }

    @Test
    fun `removes bot mention in middle of sentence`() {
        val content = text("hey @VusanBot, check USD to UAH", mention("VusanBot", offset = 4))

        assertEquals("hey, check USD to UAH", sanitizeUserText(content, botUserId, botUsername))
    }

    @Test
    fun `keeps other mentions intact`() {
        val content = text(
            "@someone ask @VusanBot about the news",
            mention("someone", offset = 0),
            mention("VusanBot", offset = 13)
        )

        assertEquals("@someone ask about the news", sanitizeUserText(content, botUserId, botUsername))
    }

    @Test
    fun `still strips text_mention when bot username is unknown`() {
        val content = text("Vusan, ping", textMention("Vusan", offset = 0, userId = botUserId))

        assertEquals("ping", sanitizeUserText(content, botUserId, botUsername = null))
    }

    @Test
    fun `removes leading bot text_mention`() {
        val content = text("Vusan, how do I make a checkbox?", textMention("Vusan", offset = 0, userId = botUserId))

        assertEquals("how do I make a checkbox?", sanitizeUserText(content, botUserId, botUsername))
    }

    @Test
    fun `keeps text_mention pointing at someone else`() {
        val content = text("Bob, ping", textMention("Bob", offset = 0, userId = botUserId + 1))

        assertEquals("Bob, ping", sanitizeUserText(content, botUserId, botUsername))
    }

    @Test
    fun `mention only becomes blank after sanitization`() {
        val content = text("@VusanBot", mention("VusanBot", offset = 0))

        assertEquals("", sanitizeUserText(content, botUserId, botUsername))
    }

    @Test
    fun `trims text without entities`() {
        val content = text("  plain text  ")

        assertEquals("plain text", sanitizeUserText(content, botUserId, botUsername))
    }

    private fun text(rawText: String, vararg entities: MessageEntity): MessageText =
        MessageText(rawText, entities.toList())

    private fun mention(username: String, offset: Int): MessageEntity =
        MessageEntity.builder()
            .type(EntityType.MENTION)
            .offset(offset)
            .length(username.length + 1)
            .build()

    private fun textMention(spanText: String, offset: Int, userId: Long): MessageEntity =
        MessageEntity.builder()
            .type(EntityType.TEXTMENTION)
            .offset(offset)
            .length(spanText.length)
            .user(User.builder().id(userId).firstName("Vusan").isBot(false).build())
            .build()
}
