package com.helltar.vusan.telegram.callback

import com.helltar.vusan.config.testConfig
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.infra.Db
import com.helltar.vusan.request.testScope
import com.helltar.vusan.tasks.NewScheduledTask
import com.helltar.vusan.tasks.Recurrence
import com.helltar.vusan.tasks.TasksRepository
import com.helltar.vusan.telegram.delivery.ChatTarget
import com.helltar.vusan.telegram.delivery.replyParameters
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CompletableFuture
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.message.SendMessageResult
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import org.telegram.telegrambots.meta.generics.TelegramClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class OwnViewHandlerTest {

    private lateinit var tempDir: Path
    private lateinit var repo: TasksRepository

    private val requests = mutableListOf<Any>()
    private var refuseReplies = false

    private val english = Messages.of(Language.ENGLISH)
    private val ann = User.builder().id(100L).firstName("Ann").isBot(false).build()

    // a reply to a press comes back as a bare success through executeWithResultAsync, as telegram answers it
    private val client: TelegramClient =
        Proxy.newProxyInstance(
            TelegramClient::class.java.classLoader,
            arrayOf(TelegramClient::class.java),
        ) { _, method, args ->
            val request = args.single()
            requests += request

            when (method.name) {
                "executeWithResultAsync" ->
                    if (refuseReplies)
                        CompletableFuture.failedFuture(TelegramApiRequestException("Bad Request: query is too old"))
                    else
                        CompletableFuture.completedFuture(SendMessageResult.callbackSuccess())

                "executeAsync" -> CompletableFuture.completedFuture(if (request is AnswerCallbackQuery) true else Message())
                else -> error("unexpected client call: ${method.name}")
            }
        } as TelegramClient

    private fun handler() = OwnViewHandler(client, TaskMenuHandler(client, repo, maxTasksPerUser = 5))

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-own-view-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
        repo = TasksRepository()
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `the button is posted in the open and carries no owner`() = runBlocking {
        handler().sendPrompt(OwnView.TASKS, ChatTarget(-200L), replyParameters(55L), english)

        val prompt = assertIs<SendMessage>(requests.single())
        assertEquals("-200", prompt.chatId)
        assertNull(prompt.ephemeralMessageParameters)
        assertEquals(english.ownViewPrompt, prompt.text)

        val button = assertIs<InlineKeyboardMarkup>(prompt.replyMarkup).keyboard.single().single()
        assertEquals("own:tasks", button.callbackData)
        assertTrue(handler().handles(button.callbackData))
    }

    // the reply names no chat and no receiver: telegram shows it to whoever pressed, with no admin right
    @Test
    fun `a press shows the presser their own tasks of this chat, for their eyes only`() = runBlocking {
        createTask(userId = 100L, chatId = -200L, title = "morning digest")
        createTask(userId = 300L, chatId = -200L, title = "someone else's digest")

        handler().handleCallback("q1", "own:tasks", ann, chatId = -200L, english)

        val reply = assertIs<SendMessage>(requests[0])
        assertContains(reply.text, "morning digest")
        assertFalse(reply.text.contains("someone else's digest"))

        val wire = JsonMapper().valueToTree<JsonNode>(reply)
        assertFalse(wire.has("chat_id"))
        assertEquals("""{"callback_query_id":"q1"}""", wire["ephemeral_message_parameters"].toString())

        val answer = assertIs<AnswerCallbackQuery>(requests[1])
        assertNull(answer.text)
    }

    @Test
    fun `a refused reply is told in an alert`() = runBlocking {
        refuseReplies = true

        handler().handleCallback("q1", "own:tasks", ann, chatId = -200L, english)

        val answer = assertIs<AnswerCallbackQuery>(requests.last())
        assertEquals(english.ownViewFailedAlert, answer.text)
        assertEquals(true, answer.showAlert)
    }

    // a button posted while a server was configured may be pressed after it went
    @Test
    fun `a sandbox press without a sandbox server says it is unavailable`() = runBlocking {
        handler().handleCallback("q1", "own:sandbox", ann, chatId = -200L, english)

        assertEquals(english.sandboxUnavailableReply, assertIs<SendMessage>(requests[0]).text)
    }

    private suspend fun createTask(userId: Long, chatId: Long, title: String): Long =
        repo.create(
            NewScheduledTask(
                scope = testScope(userId = userId, chatId = chatId),
                prompt = "run $title",
                title = title,
                recurrence = Recurrence.Once,
                timezone = ZoneId.of("UTC"),
                nextFireAt = Instant.parse("2026-07-28T12:00:00Z"),
                creatorThreadId = null,
                creatorMessageId = "1",
                creatorUsername = "tester",
                creatorDisplayName = "Test User",
                chatIsPrivate = false,
                language = Language.ENGLISH,
            ),
        )
}
