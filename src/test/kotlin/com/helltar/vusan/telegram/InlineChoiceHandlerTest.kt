package com.helltar.vusan.telegram

import com.helltar.vusan.i18n.Language
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.outbox.BotOutput
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.generics.TelegramClient

class InlineChoiceHandlerTest {

    private val messages = Messages.of(Language.ENGLISH)

    @Test
    fun `owner selection removes buttons and returns the selected option`() = runBlocking {
        val client = RecordingClient()
        val handler = InlineChoiceHandler(client.proxy) { 3L }
        val keyboard = inlineChoiceKeyboard(choice())

        val selection =
            handler.handleCallback(
                callbackQueryId = "query",
                callbackData = "choice:42:3:1",
                userId = 42L,
                chatId = -7L,
                messageId = 9,
                question = "Which format?",
                keyboard = keyboard,
                messages = messages
            )

        assertEquals(InlineChoiceSelection("Which format?", "DOCX"), selection)

        val edit = assertIs<EditMessageText>(client.requests.first())
        assertEquals("Which format?\n\n✅ Selected: DOCX", edit.text)
        assertNotNull(edit.replyMarkup)
        assertTrue(edit.replyMarkup.keyboard.isEmpty())
        assertIs<AnswerCallbackQuery>(client.requests.last())
        Unit
    }

    @Test
    fun `same choice message can only be consumed once`() = runBlocking {
        val client = RecordingClient()
        val handler = InlineChoiceHandler(client.proxy) { 3L }
        val keyboard = inlineChoiceKeyboard(choice())

        val first =
            handler.handleCallback("first", "choice:42:3:0", 42L, -7L, 9, "Which format?", keyboard, messages)

        client.requests.clear()

        val second =
            handler.handleCallback("second", "choice:42:3:1", 42L, -7L, 9, "Which format?", keyboard, messages)

        assertNotNull(first)
        assertNull(second)
        val answer = assertIs<AnswerCallbackQuery>(client.requests.single())
        assertEquals(true, answer.showAlert)
        assertContains(assertNotNull(answer.text), "no longer available")
    }

    @Test
    fun `another user cannot answer the choice`() = runBlocking {
        val client = RecordingClient()
        val handler = InlineChoiceHandler(client.proxy) { 3L }

        val selection =
            handler.handleCallback(
                callbackQueryId = "query",
                callbackData = "choice:42:3:0",
                userId = 99L,
                chatId = -7L,
                messageId = 9,
                question = "Which format?",
                keyboard = inlineChoiceKeyboard(choice()),
                messages = messages
            )

        assertNull(selection)
        assertFalse(client.requests.any { it is EditMessageText })
        val answer = assertIs<AnswerCallbackQuery>(client.requests.single())
        assertEquals(true, answer.showAlert)
        assertContains(assertNotNull(answer.text), "someone else")
    }

    @Test
    fun `choice from a cleared history is unavailable`() = runBlocking {
        val client = RecordingClient()
        var currentRevision = 3L
        val handler = InlineChoiceHandler(client.proxy) { currentRevision }
        val keyboard = inlineChoiceKeyboard(choice())
        currentRevision = 4L

        val selection =
            handler.handleCallback(
                callbackQueryId = "query",
                callbackData = "choice:42:3:0",
                userId = 42L,
                chatId = -7L,
                messageId = 9,
                question = "Which format?",
                keyboard = keyboard,
                messages = messages
            )

        assertNull(selection)
        assertFalse(client.requests.any { it is EditMessageText })
        val answer = assertIs<AnswerCallbackQuery>(client.requests.single())
        assertEquals(true, answer.showAlert)
        assertContains(assertNotNull(answer.text), "no longer available")
    }

    @Test
    fun `selection becomes escaped structured agent input`() {
        val input =
            inlineChoiceAgentInput(
                InlineChoiceSelection(
                    question = "Pick <one> & continue",
                    option = "A > B"
                )
            )

        assertContains(input, "<inline_choice>")
        assertContains(input, "<question>\nPick &lt;one&gt; &amp; continue\n</question>")
        assertContains(input, "<selected_option>\nA &gt; B\n</selected_option>")
        assertFalse(input.contains("Pick <one>"))
    }

    private fun choice() =
        BotOutput.InlineChoice(
            question = "Which format?",
            options = listOf("PDF", "DOCX"),
            ownerId = 42L,
            historyRevision = 3L
        )

    private class RecordingClient {
        val requests = mutableListOf<Any>()

        val proxy: TelegramClient =
            Proxy.newProxyInstance(
                TelegramClient::class.java.classLoader,
                arrayOf(TelegramClient::class.java)
            ) { _, method, args ->
                check(method.name == "executeAsync") { "unexpected client call: ${method.name}" }
                handle(args.single())
            } as TelegramClient

        private fun handle(request: Any): CompletableFuture<Any> {
            requests += request
            return CompletableFuture.completedFuture(if (request is AnswerCallbackQuery) true else Message())
        }
    }
}
