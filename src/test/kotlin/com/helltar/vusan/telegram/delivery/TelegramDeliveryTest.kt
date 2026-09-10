package com.helltar.vusan.telegram.delivery

import com.helltar.vusan.agent.AgentResult
import com.helltar.vusan.agent.ToolActivity
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import java.io.Serializable
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import com.helltar.vusan.delivery.Attribution
import com.helltar.vusan.delivery.AttributionReason
import com.helltar.vusan.delivery.Destination
import com.helltar.vusan.delivery.TurnDelivery
import com.helltar.vusan.request.testChat
import com.helltar.vusan.request.testUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.meta.api.methods.ActionType
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.ApiResponse
import org.telegram.telegrambots.meta.api.objects.chat.Chat
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import org.telegram.telegrambots.meta.generics.TelegramClient

class TelegramDeliveryTest {

    private val oneByte = ByteArray(1)

    @Test
    fun `maps each output kind to its matching chat action`() {
        assertEquals(ActionType.TYPING, botActionFor(BotOutput.Text("hi")))
        assertEquals(
            ActionType.TYPING,
            botActionFor(
                BotOutput.InlineChoice(
                    question = "Choose",
                    options = listOf("A", "B"),
                    ownerId = "1",
                    historyRevision = 0L
                )
            )
        )
        assertEquals(ActionType.UPLOAD_PHOTO, botActionFor(BotOutput.Photo(oneByte, "p.png")))
        assertEquals(
            ActionType.UPLOAD_PHOTO,
            botActionFor(BotOutput.PhotoGroup(listOf(BotOutput.Photo(oneByte, "a.png"), BotOutput.Photo(oneByte, "b.png"))))
        )
        assertEquals(ActionType.UPLOAD_DOCUMENT, botActionFor(BotOutput.Document(oneByte, "d.txt")))
        assertEquals(ActionType.UPLOAD_VIDEO, botActionFor(BotOutput.Video(oneByte, "v.mp4")))
        assertEquals(ActionType.UPLOAD_VIDEO, botActionFor(BotOutput.Animation(url = "https://example.com/a.gif")))
        assertEquals(ActionType.RECORD_VIDEO_NOTE, botActionFor(BotOutput.VideoNote(oneByte)))
        assertEquals(ActionType.RECORD_VOICE, botActionFor(BotOutput.Voice(oneByte)))
        assertEquals(
            ActionType.UPLOAD_DOCUMENT,
            botActionFor(BotOutput.Audio(oneByte, "s.mp3", title = "t", performer = "p"))
        )
    }

    @Test
    fun `reactions get no chat action`() {
        assertNull(botActionFor(BotOutput.Reaction(messageId = 1, emoji = "👍")))
    }

    @Test
    fun `a sticker telegram will not accept is reported to the catalog`() = runBlocking {
        val rejected = mutableListOf<Long>()
        val client = RejectingClient("Bad Request: wrong remote file identifier specified")

        deliverSticker(TelegramDelivery(client.proxy, onStickerRejected = { rejected += it }))

        assertEquals(listOf(42L), rejected)
    }

    @Test
    fun `a sticker refused for reasons of its own is not blamed on the catalog`() = runBlocking {
        val rejected = mutableListOf<Long>()
        // a group where admins restricted stickers rejects every one of them; the catalog is shared
        // by every chat, so this must not be read as the sticker being broken.
        val client = RejectingClient("Bad Request: not enough rights to send stickers to the chat")

        deliverSticker(TelegramDelivery(client.proxy, onStickerRejected = { rejected += it }))

        assertTrue(rejected.isEmpty(), "a chat-level refusal was mistaken for a dead file_id")
    }

    @Test
    fun `a scheduled send into a chat the bot was removed from reports the chat as gone`() = runBlocking {
        val client = RejectingClient("Forbidden: bot was kicked from the supergroup chat")

        val outcome =
            TelegramDelivery(client.proxy).deliver(
                TurnDelivery(
                    result = AgentResult(outputs = emptyList(), comment = "The weekly summary is ready."),
                    destination = Destination(testChat(-1)),
                    recipient = testUser(2),
                    language = Language.ENGLISH
                )
            )

        assertTrue(outcome.isUnreachable, "a kicked bot must not keep firing tasks into that chat")
    }

    @Test
    fun `a send rejected for its own content leaves the chat usable`() = runBlocking {
        val client = RejectingClient("Bad Request: message is too long")

        val outcome =
            TelegramDelivery(client.proxy).deliver(
                TurnDelivery(
                    result = AgentResult(outputs = emptyList(), comment = "The weekly summary is ready."),
                    destination = Destination(testChat(-1)),
                    recipient = testUser(2),
                    language = Language.ENGLISH
                )
            )

        assertFalse(outcome.isUnreachable, "one rejected message must not park the chat's tasks")
    }

    @Test
    fun `nothing else is attempted once the chat turns out to be gone`() = runBlocking {
        val client = RejectingClient("Forbidden: bot was kicked from the supergroup chat")
        val outbox =
            BotOutbox().apply {
                enqueue(BotOutput.Photo(oneByte, "first.png"))
                enqueue(BotOutput.Photo(oneByte, "second.png"))
                enqueue(BotOutput.Photo(oneByte, "third.png"))
            }

        val outcome =
            TelegramDelivery(client.proxy).deliver(
                TurnDelivery(
                    result = AgentResult(outputs = outbox.pending, comment = null),
                    destination = Destination(testChat(-1)),
                    recipient = testUser(2),
                    language = Language.ENGLISH
                )
            )

        assertTrue(outcome.isUnreachable)
        // the chat action of the first item, then its send. the two remaining photos are never tried.
        assertEquals(2, client.calls)
    }

    // the buttons sit on the bot's own question, but the answer belongs under the message the user wrote,
    // the same place a plain reply would land.
    @Test
    fun `a callback answer replies to the message the exchange started from`() = runBlocking {
        val client = RecordingClient()

        TelegramDelivery(client.proxy).sendCallback(
            result = AgentResult(outputs = emptyList(), comment = "The reminder is set."),
            message = choiceMessage(),
            originMessageId = 11L,
            userId = 2L,
            messages = Messages.of(Language.ENGLISH)
        )

        assertEquals(11, client.replyTargets.single())
    }

    @Test
    fun `a callback answer falls back to the question it belongs to`() = runBlocking {
        val client = RecordingClient()

        TelegramDelivery(client.proxy).sendCallback(
            result = AgentResult(outputs = emptyList(), comment = "The reminder is set."),
            message = choiceMessage(),
            originMessageId = null,
            userId = 2L,
            messages = Messages.of(Language.ENGLISH)
        )

        assertEquals(77, client.replyTargets.single())
    }

    // the turn announced its plan into the live status while it was still working, so the chat already
    // has those words; delivery owes the user only what is left.
    @Test
    fun `an announcement already in the chat is not sent again`() = runBlocking {
        val client = RecordingClient()
        val outbox =
            BotOutbox().apply {
                recordDelivered("I will build the game")
                enqueueText("here it is")
            }

        TelegramDelivery(client.proxy).send(
            message = choiceMessage(),
            result = AgentResult(outputs = outbox.pending, comment = null)
        )

        assertEquals(listOf("here it is"), client.sentTexts)
    }

    @Test
    fun `a scheduled fire names its owner by username`() = runBlocking {
        val client = RecordingClient()

        deliverScheduled(
            client,
            Attribution(
                anchorMessageId = null,
                person = testUser(100),
                displayName = "Helltar",
                username = "helltar",
                reason = AttributionReason.SCHEDULED
            )
        )

        assertEquals("⏰ Scheduled by @helltar", client.sentTexts.first())
    }

    // a notice is parsed as HTML, so the link has to be written as HTML: markdown would arrive as
    // literal brackets, and a display name is the person's own text.
    @Test
    fun `an owner without a username is linked by their account`() = runBlocking {
        val client = RecordingClient()

        deliverScheduled(
            client,
            Attribution(
                anchorMessageId = null,
                person = testUser(100),
                displayName = "Ann & <b>Bob</b>",
                username = null,
                reason = AttributionReason.FOLLOW_UP
            )
        )

        assertEquals(
            """💬 Following up with <a href="tg://user?id=100">Ann &amp; &lt;b&gt;Bob&lt;/b&gt;</a>""",
            client.sentTexts.first()
        )
    }

    // a task fired in a forum has no message to anchor to once the one that created it is gone, so the
    // topic is the only thing keeping the answer out of the group's General.
    @Test
    fun `a scheduled fire names the topic it was set up in`() = runBlocking {
        val client = RecordingClient()

        TelegramDelivery(client.proxy).deliver(
            TurnDelivery(
                result = AgentResult(outputs = emptyList(), comment = "The weekly summary is ready."),
                destination = Destination(testChat(-7), threadId = "42"),
                recipient = testUser(2),
                language = Language.ENGLISH
            )
        )

        assertEquals(listOf<Int?>(42), client.threadIds)
    }

    @Test
    fun `a fire in a chat without topics names none`() = runBlocking {
        val client = RecordingClient()

        TelegramDelivery(client.proxy).deliver(
            TurnDelivery(
                result = AgentResult(outputs = emptyList(), comment = "The weekly summary is ready."),
                destination = Destination(testChat(-7)),
                recipient = testUser(2),
                language = Language.ENGLISH
            )
            )

        assertEquals(listOf<Int?>(null), client.threadIds)
    }

    @Test
    fun `an answer in a forum topic stays in it`() = runBlocking {
        val client = RecordingClient()
        val outbox = BotOutbox().apply { enqueueText("here it is") }

        TelegramDelivery(client.proxy).send(
            message = topicMessage(),
            result = AgentResult(outputs = outbox.pending, comment = null)
        )

        assertEquals(listOf<Int?>(42), client.threadIds)
    }

    // outside a forum the same field identifies a reply chain, and sending with it is rejected — so the
    // answer must name no topic at all.
    @Test
    fun `a reply thread in an ordinary supergroup is not treated as a topic`() = runBlocking {
        val client = RecordingClient()
        val outbox = BotOutbox().apply { enqueueText("here it is") }

        TelegramDelivery(client.proxy).send(
            message = topicMessage(isTopic = false),
            result = AgentResult(outputs = outbox.pending, comment = null)
        )

        assertEquals(listOf<Int?>(null), client.threadIds)
    }

    private fun choiceMessage() =
        Message().apply {
            messageId = 77
            chat = Chat.builder().id(-7L).type("supergroup").build()
        }

    // built rather than mutated: `isTopicMessage` is ambiguous as a property, since the library carries
    // both a nullable field and a null-safe accessor of that name.
    private fun topicMessage(isTopic: Boolean = true): Message =
        Message.builder()
            .messageId(77)
            .messageThreadId(42)
            .isTopicMessage(isTopic)
            .chat(Chat.builder().id(-7L).type("supergroup").build())
            .build()

    private class RecordingClient {

        val replyTargets = mutableListOf<Int?>()
        val sentTexts = mutableListOf<String>()
        val threadIds = mutableListOf<Int?>()

        val proxy: TelegramClient =
            Proxy.newProxyInstance(
                TelegramClient::class.java.classLoader,
                arrayOf(TelegramClient::class.java)
            ) { _, method, args ->
                check(method.name == "executeAsync") { "unexpected client call: ${method.name}" }
                handle(args.single())
            } as TelegramClient

        private fun handle(request: Any): CompletableFuture<Any> =
            when (request) {
                is SendMessage -> {
                    replyTargets += request.replyParameters?.messageId
                    sentTexts += request.text
                    threadIds += request.messageThreadId
                    CompletableFuture.completedFuture(Message())
                }

                else -> CompletableFuture.completedFuture(true)
            }
    }

    private suspend fun deliverScheduled(client: RecordingClient, attribution: Attribution) {
        TelegramDelivery(client.proxy).deliver(
            TurnDelivery(
                result = AgentResult(outputs = emptyList(), comment = "The weekly summary is ready."),
                destination = Destination(testChat(-7)),
                recipient = testUser(100),
                language = Language.ENGLISH,
                attribution = attribution
            )
        )
    }

    private suspend fun deliverSticker(delivery: TelegramDelivery) {
        val outbox = BotOutbox().apply { enqueue(BotOutput.Sticker("dead-file-id", catalogId = 42L)) }

        delivery.deliver(
                TurnDelivery(
            result = AgentResult(outputs = outbox.pending, comment = null),
            destination = Destination(testChat(1)),
            recipient = testUser(2),
            language = Language.ENGLISH
                )
            )
    }

    private class RejectingClient(private val description: String) {

        var calls = 0
            private set

        val proxy: TelegramClient =
            Proxy.newProxyInstance(
                TelegramClient::class.java.classLoader,
                arrayOf(TelegramClient::class.java)
            ) { _, method, _ ->
                check(method.name == "executeAsync") { "unexpected client call: ${method.name}" }
                calls++

                CompletableFuture.failedFuture<Any>(
                    TelegramApiRequestException(
                        "Error executing request",
                        ApiResponse.builder<Serializable>()
                            .ok(false)
                            .errorCode(400)
                            .errorDescription(description)
                            .build()
                    )
                )
            } as TelegramClient
    }

    @Test
    fun `translates a media-producing activity to its chat action`() {
        assertEquals(ActionType.UPLOAD_PHOTO, chatActionFor(ToolActivity.DRAWING))
        assertEquals(ActionType.UPLOAD_PHOTO, chatActionFor(ToolActivity.SEARCHING_IMAGES))
        assertEquals(ActionType.UPLOAD_VIDEO, chatActionFor(ToolActivity.DOWNLOADING_VIDEO))
        assertEquals(ActionType.UPLOAD_VIDEO, chatActionFor(ToolActivity.SEARCHING_GIF))
        assertEquals(ActionType.RECORD_VOICE, chatActionFor(ToolActivity.SPEAKING))
        assertEquals(ActionType.UPLOAD_DOCUMENT, chatActionFor(ToolActivity.SENDING_FILE))
        assertEquals(ActionType.UPLOAD_DOCUMENT, chatActionFor(ToolActivity.DOWNLOADING_AUDIO))
    }

    @Test
    fun `everything that produces no media reads as typing`() {
        assertEquals(ActionType.TYPING, chatActionFor(null))
        assertEquals(ActionType.TYPING, chatActionFor(ToolActivity.WRITING))
        assertEquals(ActionType.TYPING, chatActionFor(ToolActivity.SEARCHING_WEB))
        assertEquals(ActionType.TYPING, chatActionFor(ToolActivity.RUNNING_CODE))
        assertEquals(ActionType.TYPING, chatActionFor(ToolActivity.WATCHING_VIDEO))
    }
}
