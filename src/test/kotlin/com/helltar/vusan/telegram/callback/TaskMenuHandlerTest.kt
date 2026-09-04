package com.helltar.vusan.telegram.callback

import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.HostedLlmProvider
import com.helltar.vusan.config.LlmProviderConfig
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.infra.Db
import com.helltar.vusan.tasks.NewScheduledTask
import com.helltar.vusan.tasks.Recurrence
import com.helltar.vusan.tasks.TasksRepository
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.ParseMode
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.generics.TelegramClient

private const val MAX_TELEGRAM_TEXT_CHARS = 4096

class TaskMenuHandlerTest {

    private lateinit var tempDir: Path
    private lateinit var repo: TasksRepository
    private lateinit var client: RecordingClient
    private lateinit var handler: TaskMenuHandler
    private var currentTime = Instant.parse("2026-07-28T10:30:00Z")

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-task-menu-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
        repo = TasksRepository()
        client = RecordingClient()
        handler = TaskMenuHandler(client.proxy, repo, maxTasksPerUser = 5) { currentTime }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `group menu lists only the owners tasks from that chat`() = runBlocking {
        val visibleId =
            createTask(
                userId = 100L,
                chatId = -200L,
                title = "group <report> & news",
                recurrence = Recurrence.Cron("0 9 * * *")
            )
        createTask(userId = 100L, chatId = -300L, title = "other group report")
        createTask(userId = 200L, chatId = -200L, title = "other user's report")

        handler.sendMenu(
            chatId = -200L,
            userId = 100L,
            replyToMessageId = 55L,
            chatIsPrivate = false,
            messages = Messages.of(Language.ENGLISH)
        )

        val request = assertIs<SendMessage>(client.requests.single())
        assertEquals(ParseMode.HTML, request.parseMode)
        assertContains(
            request.text,
            "<b>🗓 Your scheduled tasks in this chat</b>\n\n" +
                    "<i>In this chat: 1\nAcross all chats: 2 · limit: 5</i>"
        )
        assertContains(request.text, "<b>#$visibleId · group &lt;report&gt; &amp; news</b>")
        assertFalse(request.text.contains("group <report> & news"))
        assertContains(request.text, "🕒 2026-07-28 12:00 · UTC")
        assertContains(request.text, "🔁 cron · <code>0 9 * * *</code>")
        assertContains(request.text, "🟢 Active")
        assertFalse(request.text.contains("other group report"))
        assertFalse(request.text.contains("other user's report"))

        val keyboard = assertIs<InlineKeyboardMarkup>(request.replyMarkup)
        assertEquals("tasks:100:pause:$visibleId", keyboard.keyboard.first()[0].callbackData)
        assertEquals("tasks:100:confirm:$visibleId", keyboard.keyboard.first()[1].callbackData)
        assertTrue(keyboard.keyboard.flatten().all { it.callbackData.toByteArray().size <= 64 })
    }

    @Test
    fun `empty menus show task count and limit without a fraction`() = runBlocking {
        handler.sendMenu(
            chatId = 100L,
            userId = 100L,
            replyToMessageId = 55L,
            chatIsPrivate = true,
            messages = Messages.of(Language.ENGLISH)
        )

        val privateMenu = assertIs<SendMessage>(client.requests.single())
        assertContains(
            privateMenu.text,
            "<b>🗓 Your scheduled tasks</b>\n\n<i>Tasks: 0 · limit: 5</i>\n\n"
        )

        client.requests.clear()

        handler.sendMenu(
            chatId = -200L,
            userId = 100L,
            replyToMessageId = 55L,
            chatIsPrivate = false,
            messages = Messages.of(Language.ENGLISH)
        )

        val groupMenu = assertIs<SendMessage>(client.requests.single())
        assertContains(
            groupMenu.text,
            "<b>🗓 Your scheduled tasks in this chat</b>\n\n" +
                    "<i>In this chat: 0\nAcross all chats: 0 · limit: 5</i>\n\n"
        )
    }

    @Test
    fun `menu stays within telegram's message limit when the task limit is raised`() = runBlocking {
        val roomyHandler = TaskMenuHandler(client.proxy, repo, maxTasksPerUser = 100) { currentTime }
        val taskCount = 40

        repeat(taskCount) { index ->
            createTask(
                userId = 100L,
                chatId = 100L,
                title = "long standing reminder number $index ".repeat(4),
                recurrence = Recurrence.Cron("0 9 * * *")
            )
        }

        roomyHandler.sendMenu(
            chatId = 100L,
            userId = 100L,
            replyToMessageId = 55L,
            chatIsPrivate = true,
            messages = Messages.of(Language.ENGLISH)
        )

        val request = assertIs<SendMessage>(client.requests.single())
        assertTrue(request.text.length <= MAX_TELEGRAM_TEXT_CHARS, "menu text was ${request.text.length} characters")

        val keyboard = assertIs<InlineKeyboardMarkup>(request.replyMarkup)
        val shown = keyboard.keyboard.size - 1

        assertTrue(shown in 1 until taskCount, "expected a truncated task list, got $shown rows")
        assertContains(request.text, "${taskCount - shown} more didn't fit here")
        assertContains(request.text, "<i>Tasks: $taskCount · limit: 100</i>")
    }

    @Test
    fun `pause and resume update the task and menu in place`() = runBlocking {
        val id = createTask(userId = 100L, chatId = 100L, title = "hydrate")

        handler.handleCallback(
            callbackQueryId = "pause-query",
            callbackData = "tasks:100:pause:$id",
            userId = 100L,
            chatId = 100L,
            messageId = 9,
            chatIsPrivate = true,
            messages = Messages.of(Language.ENGLISH)
        )

        assertTrue(assertNotNull(repo.findEnabledForUser(100L, id)).paused)
        val pausedEdit = assertIs<EditMessageText>(client.requests.first())
        assertEquals(ParseMode.HTML, pausedEdit.parseMode)
        assertContains(pausedEdit.text, "<i>Tasks: 1 · limit: 5</i>")
        assertContains(pausedEdit.text, "⏸ Paused")
        val pausedKeyboard = assertIs<InlineKeyboardMarkup>(pausedEdit.replyMarkup)
        assertEquals("tasks:100:resume:$id", pausedKeyboard.keyboard.first()[0].callbackData)
        assertIs<AnswerCallbackQuery>(client.requests.last())

        client.requests.clear()

        handler.handleCallback(
            callbackQueryId = "resume-query",
            callbackData = "tasks:100:resume:$id",
            userId = 100L,
            chatId = 100L,
            messageId = 9,
            chatIsPrivate = true,
            messages = Messages.of(Language.ENGLISH)
        )

        assertFalse(assertNotNull(repo.findEnabledForUser(100L, id)).paused)
        val resumedEdit = assertIs<EditMessageText>(client.requests.first())
        val resumedKeyboard = assertIs<InlineKeyboardMarkup>(resumedEdit.replyMarkup)
        assertEquals("tasks:100:pause:$id", resumedKeyboard.keyboard.first()[0].callbackData)
        assertIs<AnswerCallbackQuery>(client.requests.last())
        Unit
    }

    @Test
    fun `resume advances an elapsed recurring task to its next future slot`() = runBlocking {
        val id =
            createTask(
                userId = 100L,
                chatId = 100L,
                title = "hourly report",
                recurrence = Recurrence.Every(1.hours),
                nextFireAt = Instant.parse("2026-07-28T08:00:00Z")
            )
        repo.pauseForUser(100L, id)

        handler.handleCallback(
            callbackQueryId = "resume-query",
            callbackData = "tasks:100:resume:$id",
            userId = 100L,
            chatId = 100L,
            messageId = 9,
            chatIsPrivate = true,
            messages = Messages.of(Language.ENGLISH)
        )

        val task = assertNotNull(repo.findEnabledForUser(100L, id))
        assertFalse(task.paused)
        assertEquals(Instant.parse("2026-07-28T11:00:00Z"), task.nextFireAt)
    }

    @Test
    fun `elapsed one-time task stays paused and shows an alert`() = runBlocking {
        val id =
            createTask(
                userId = 100L,
                chatId = 100L,
                title = "old reminder",
                nextFireAt = Instant.parse("2026-07-28T08:00:00Z")
            )
        repo.pauseForUser(100L, id)
        client.requests.clear()

        handler.handleCallback(
            callbackQueryId = "resume-query",
            callbackData = "tasks:100:resume:$id",
            userId = 100L,
            chatId = 100L,
            messageId = 9,
            chatIsPrivate = true,
            messages = Messages.of(Language.ENGLISH)
        )

        assertTrue(assertNotNull(repo.findEnabledForUser(100L, id)).paused)
        val answer = assertIs<AnswerCallbackQuery>(client.requests.single())
        assertEquals(true, answer.showAlert)
        assertContains(assertNotNull(answer.text), "can't be resumed")
    }

    @Test
    fun `another user cannot operate the menu`() = runBlocking {
        val id = createTask(userId = 100L, chatId = -200L, title = "private task")

        handler.handleCallback(
            callbackQueryId = "foreign-query",
            callbackData = "tasks:100:pause:$id",
            userId = 200L,
            chatId = -200L,
            messageId = 9,
            chatIsPrivate = false,
            messages = Messages.of(Language.ENGLISH)
        )

        assertFalse(assertNotNull(repo.findEnabledForUser(100L, id)).paused)
        val answer = assertIs<AnswerCallbackQuery>(client.requests.single())
        assertEquals(true, answer.showAlert)
        assertContains(assertNotNull(answer.text), "someone else")
    }

    @Test
    fun `cancel requires confirmation before deleting`() = runBlocking {
        val id = createTask(userId = 100L, chatId = 100L, title = "weekly <cleanup>")

        handler.handleCallback(
            callbackQueryId = "confirm-query",
            callbackData = "tasks:100:confirm:$id",
            userId = 100L,
            chatId = 100L,
            messageId = 9,
            chatIsPrivate = true,
            messages = Messages.of(Language.ENGLISH)
        )

        assertNotNull(repo.findEnabledForUser(100L, id))
        val confirmation = assertIs<EditMessageText>(client.requests.first())
        assertEquals(ParseMode.HTML, confirmation.parseMode)
        assertContains(confirmation.text, "<b>Delete task #$id · weekly &lt;cleanup&gt;?</b>")
        assertFalse(confirmation.text.contains("weekly <cleanup>"))
        val keyboard = assertIs<InlineKeyboardMarkup>(confirmation.replyMarkup)
        assertEquals("tasks:100:delete:$id", keyboard.keyboard.single()[0].callbackData)
        assertEquals("tasks:100:back", keyboard.keyboard.single()[1].callbackData)

        client.requests.clear()

        handler.handleCallback(
            callbackQueryId = "delete-query",
            callbackData = "tasks:100:delete:$id",
            userId = 100L,
            chatId = 100L,
            messageId = 9,
            chatIsPrivate = true,
            messages = Messages.of(Language.ENGLISH)
        )

        assertNull(repo.findEnabledForUser(100L, id))
        assertIs<EditMessageText>(client.requests.first())
        assertIs<AnswerCallbackQuery>(client.requests.last())
        Unit
    }

    private suspend fun createTask(
        userId: Long,
        chatId: Long,
        title: String,
        recurrence: Recurrence = Recurrence.Once,
        nextFireAt: Instant = Instant.parse("2026-07-28T12:00:00Z")
    ): Long =
        repo.create(
            NewScheduledTask(
                userId = userId,
                chatId = chatId,
                prompt = "run $title",
                title = title,
                recurrence = recurrence,
                timezone = ZoneId.of("UTC"),
                nextFireAt = nextFireAt,
                creatorMessageId = 1L,
                creatorUsername = "tester",
                creatorDisplayName = "Test User",
                chatIsPrivate = chatId > 0L,
                language = Language.ENGLISH
            )
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

            val response =
                when (request) {
                    is AnswerCallbackQuery -> true
                    else -> Message()
                }

            return CompletableFuture.completedFuture(response)
        }
    }

    private fun testConfig(dbPath: String) =
        AppConfig(
            agentMaxIterations = 70,
            allowedIds = emptySet(),
            appearance = null,
            databasePath = dbPath,
            elevenLabsApiKey = null,
            elevenLabsTts = null,
            giphyApiKey = null,
            llmProvider = LlmProviderConfig.Hosted(
                provider = HostedLlmProvider.OPENAI,
                apiKey = "test",
                model = "test",
                requestTimeout = 60.seconds
            ),
            maxFollowUpsPerUser = 3,
            maxMemoryPerScope = 10,
            maxTasksPerUser = 5,
            openAiImageApiKey = null,
            openAiImage = null,
            openAiStt = null,
            openAiVision = null,
            workspaceMaxTimeoutSeconds = 600L,
            workspaceToken = null,
            workspaceUrl = null,
            searxngUrl = null,
            selfImageFile = null,
            personality = null,
            taskMaxLatenessMinutes = 60L,
            tavilyApiKey = null,
            telegramBotToken = "test",
            ytDlpCookiesFile = null
        )
}
