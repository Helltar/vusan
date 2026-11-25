package com.helltar.vusan.telegram.callback

import com.helltar.vusan.config.testConfig
import com.helltar.vusan.infra.Db
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.WidgetRequests
import com.helltar.vusan.request.AccessPolicy
import com.helltar.vusan.telegram.RecordedCall
import com.helltar.vusan.telegram.WidgetRegistry
import com.helltar.vusan.telegram.delivery.ChatTarget
import com.helltar.vusan.telegram.delivery.SentWidget
import com.helltar.vusan.telegram.recordingTelegramClient
import com.helltar.vusan.telegram.tallyPublicData
import com.helltar.vusan.telegram.telegramUser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.api.objects.chat.Chat
import org.telegram.telegrambots.meta.api.objects.widget.WidgetQuery
import tools.jackson.databind.json.JsonMapper

class WidgetQueryHandlerTest {

    private lateinit var tempDir: Path
    private lateinit var registry: WidgetRegistry

    private val calls = mutableListOf<RecordedCall>()
    private val turns = mutableListOf<String>()

    private val mapper = JsonMapper()

    private val ann = User.builder().id(42L).firstName("Ann").isBot(false).build()
    private val group = Chat.builder().id(-100L).type("supergroup").title("Board games").build()

    private fun handler(policy: AccessPolicy = AccessPolicy(owner = telegramUser(42L).key)) =
        WidgetQueryHandler(recordingTelegramClient(calls), registry, policy) { _, user, widget, data ->
            turns += "${user.id}:${widget.messageId}:$data"
        }

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-widget-queries-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
        registry = WidgetRegistry()
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `a press in a counting widget is answered with the counts and published`() = runBlocking {
        registry.remember(
            ChatTarget(-100L),
            SentWidget(7, widgetId = null),
            BotOutput.Widget("<p>poll</p>", "Lunch poll", WidgetRequests.TALLY, publicData = tallyPublicData(0, emptyList())),
        )

        handler().handle(WidgetPress("q1", ann, "pizza", fromWallet = false, chat = group, messageId = 7))

        assertEquals(listOf("answerWidgetQuery", "editMessageWidgetData"), calls.map { it.method })
        assertEquals("""{"rev":1,"counts":{"pizza":1},"total":1,"mine":"pizza"}""", calls[0].body["data"].asString())
        assertEquals("""{"rev":1,"counts":{"pizza":1},"total":1}""", calls[1].body["public_data"].asString())
    }

    @Test
    fun `a shared widget's counts go to every copy at once`() = runBlocking {
        registry.remember(ChatTarget(-100L), SentWidget(7, widgetId = "88"), BotOutput.Widget("<p>poll</p>", "Poll", WidgetRequests.TALLY, shared = true))

        handler().handle(WidgetPress("q1", ann, "in", fromWallet = false, chat = null, messageId = null, widgetId = "88"))

        assertEquals(listOf("answerWidgetQuery", "editSharedWidgetData"), calls.map { it.method })
        assertEquals("88", calls[1].body["widget_id"].asString())
    }

    @Test
    fun `a press in an agent widget is answered at once and becomes the presser's turn`() = runBlocking {
        registry.remember(ChatTarget(-100L), SentWidget(7, widgetId = null), BotOutput.Widget("<p>board</p>", "Chess", WidgetRequests.AGENT))

        handler().handle(WidgetPress("q1", ann, "move:e4", fromWallet = false, chat = group, messageId = 7))

        assertEquals(listOf("answerWidgetQuery"), calls.map { it.method })
        assertTrue(calls[0].body["data"] == null)
        assertEquals(listOf("42:7:move:e4"), turns)
    }

    @Test
    fun `a forwarded copy of an agent widget starts no turn`() = runBlocking {
        registry.remember(ChatTarget(-100L), SentWidget(7, widgetId = "88"), BotOutput.Widget("<p>board</p>", "Chess", WidgetRequests.AGENT, shared = true))

        handler().handle(WidgetPress("q1", ann, "move:e4", fromWallet = false, chat = null, messageId = null, widgetId = "88"))

        assertEquals(listOf("answerWidgetQuery"), calls.map { it.method })
        assertTrue(turns.isEmpty())
    }

    @Test
    fun `someone the bot does not answer starts no turn`() = runBlocking {
        registry.remember(ChatTarget(-100L), SentWidget(7, widgetId = null), BotOutput.Widget("<p>board</p>", "Chess", WidgetRequests.AGENT))

        handler(AccessPolicy()).handle(WidgetPress("q1", ann, "move:e4", fromWallet = false, chat = group, messageId = 7))

        assertEquals(listOf("answerWidgetQuery"), calls.map { it.method })
        assertTrue(turns.isEmpty())
    }

    @Test
    fun `a press in a widget the bot does not know is answered with nothing`() = runBlocking {
        handler().handle(WidgetPress("q1", ann, "x", fromWallet = false, chat = group, messageId = 99))

        assertEquals(listOf("answerWidgetQuery"), calls.map { it.method })
        assertTrue(calls[0].body["data"] == null)
    }

    // the library reads both presses off the update; a shared copy names only the widget
    @Test
    fun `both kinds of press read off an update`() {
        val pressed =
            listOf(
                """{"update_id":1,"widget_query":{"id":"q1","from":{"id":42,"is_bot":false,"first_name":"Ann"},""" +
                        """"chat":{"id":-100,"type":"supergroup","title":"Board games"},"message_id":7,"chat_instance":"-5",""" +
                        """"type":{"type":"data"},"data":"pizza","widget_id":"5434093692069616148"}}""",
                """{"update_id":2,"shared_widget_query":{"id":"q2","from":{"id":42,"is_bot":false,"first_name":"Ann"},""" +
                        """"type":{"type":"data"},"data":"sushi","widget_id":"5434093692069616148"}}""",
            ).map { json ->
                val update = mapper.readValue(json, Update::class.java)

                assertNotNull(update.widgetQuery?.let(::WidgetPress) ?: update.sharedWidgetQuery?.let(::WidgetPress))
            }

        assertEquals(listOf(false, true), pressed.map { it.fromCopy })
        assertEquals(listOf("pizza", "sushi"), pressed.map { it.data })
        assertEquals(setOf("5434093692069616148"), pressed.map { it.widgetId }.toSet())
        assertTrue(pressed.none { it.fromWallet })
    }

    @Test
    fun `a wallet's signature is not a vote`() = runBlocking {
        registry.remember(ChatTarget(-100L), SentWidget(7, widgetId = null), BotOutput.Widget("<p>poll</p>", "Poll", WidgetRequests.TALLY))

        val query =
            mapper.readValue(
                """{"id":"q1","from":{"id":42,"is_bot":false,"first_name":"Ann"},""" +
                        """"chat":{"id":-100,"type":"supergroup","title":"Board games"},"message_id":7,"chat_instance":"-5",""" +
                        """"type":{"type":"sign_data","wallet":{"address":"EQ","amount":"0"},"sign_data":"c2ln"},"data":"pizza"}""",
                WidgetQuery::class.java,
            )

        handler().handle(WidgetPress(query))

        assertEquals(listOf("answerWidgetQuery"), calls.map { it.method })
        assertTrue(registry.find(-100L, 7)?.publicData == null)
    }
}
