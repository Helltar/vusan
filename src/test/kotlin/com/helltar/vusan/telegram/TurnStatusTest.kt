package com.helltar.vusan.telegram

import com.helltar.vusan.agent.ToolActivity
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.telegram.delivery.ChatTarget
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.meta.api.methods.reactions.SetMessageReaction
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.reactions.ReactionTypeEmoji
import org.telegram.telegrambots.meta.generics.TelegramClient

class TurnStatusTest {

    @Test
    fun `the running line sits under the plan and carries its own ellipsis`() {
        assertEquals(
            "I will build the game\n\nRunning code…",
            statusMessageText(plan = "I will build the game", label = "Running code"),
        )
    }

    @Test
    fun `either half stands alone`() {
        assertEquals("Searching the web…", statusMessageText(plan = null, label = "Searching the web"))
        assertEquals("I will build the game", statusMessageText(plan = "I will build the game", label = null))
    }

    // the bubble opens on the first named activity or the model's first words; before either there is nothing
    // to show.
    @Test
    fun `a fallback note sits under the running line`() {
        assertEquals(
            "I will build the game\n\n\uD83D\uDCBB Running code…\n\u21B3 on the fallback model: <code>gpt-5.4-mini</code>",
            statusMessageText(
                plan = "I will build the game",
                label = "\uD83D\uDCBB Running code",
                fallbackNote = Messages.of(Language.ENGLISH).fallbackModelNote("gpt-5.4-mini", primaryBackIn = null),
            ),
        )

        // a turn with nothing named yet still says where it answers from
        assertEquals(
            "\u21B3 on the fallback model: <code>gpt-5.4-mini</code>",
            statusMessageText(plan = null, label = null, fallbackNote = Messages.of(Language.ENGLISH).fallbackModelNote("gpt-5.4-mini", primaryBackIn = null)),
        )
    }

    @Test
    fun `a fallback note says when the usual model is due back, if the provider said`() {
        assertEquals(
            "\u21B3 on the fallback model: <code>gpt-5.4-mini</code>\n\u21B3 the usual one is back in about <b>2h 15min</b>",
            Messages.of(Language.ENGLISH).fallbackModelNote("gpt-5.4-mini", primaryBackIn = 135.minutes),
        )
    }

    // the status reports what the transcript records as said: a plan the chat refused was never read, and the
    // tool queues those words with the answer.
    @Test
    fun `only words that reached the chat are reported as posted`() = runBlocking {
        val posted = mutableListOf<String>()

        statusOver(chatAccepting(true), posted).apply {
            say("I will build the game")
            send("halfway there")
        }

        assertEquals(listOf("I will build the game", "halfway there"), posted)

        posted.clear()

        statusOver(chatAccepting(false), posted).apply {
            say("I will build the game")
            send("halfway there")
        }

        assertTrue(posted.isEmpty())
    }

    // raised again with the plan in it, the bubble would read "I will build the game" under "halfway there",
    // a plan after its own progress.
    @Test
    fun `a message sent mid-turn leaves the plan above it and moves only the running line under it`() = runBlocking {
        val calls = mutableListOf<String>()

        statusOver(recordingChat(calls), posted = mutableListOf()).apply {
            say("I will build the game")
            showActivity(ToolActivity.RUNNING_CODE, mayOpen = true)
            send("halfway there")
            finish()
        }

        assertEquals(
            listOf(
                "send #1 [I will build the game] button=true",
                "edit #1 [I will build the game\n\n💻 Running code…] button=true",
                "send #2 [halfway there] button=false",
                "edit #1 [I will build the game] button=false",
                "send #3 [💻 Running code…] button=true",
                "delete #3",
            ),
            calls,
        )
    }

    // slow mode rations the bot's messages, and a running line alone never earns one
    @Test
    fun `in slow mode a message sent mid-turn raises no bubble for the running line`() = runBlocking {
        val calls = mutableListOf<String>()

        statusOver(recordingChat(calls), posted = mutableListOf(), activityOpensIt = false).apply {
            say("I will build the game")
            showActivity(ToolActivity.RUNNING_CODE, mayOpen = true)
            send("halfway there")
            showActivity(ToolActivity.SEARCHING_WEB, mayOpen = true)
            finish()
        }

        assertEquals(
            listOf(
                "send #1 [I will build the game] button=true",
                "edit #1 [I will build the game\n\n💻 Running code…] button=true",
                "send #2 [halfway there] button=false",
                "edit #1 [I will build the game] button=false",
            ),
            calls,
        )
    }

    // a queued turn, or one in a slow-mode chat, has nothing else to show the message was heard
    @Test
    fun `an acknowledged turn marks its message as received and takes the mark off when it ends`() = runBlocking {
        val reactions = mutableListOf<List<String>>()
        val status = statusOver(recordingReactions(reactions), posted = mutableListOf(), acknowledges = true)

        status.acknowledge()
        status.finish()

        assertEquals(listOf(listOf("👀"), emptyList()), reactions)
    }

    @Test
    fun `a turn with something else to show touches no reaction`() = runBlocking {
        val reactions = mutableListOf<List<String>>()
        val status = statusOver(recordingReactions(reactions), posted = mutableListOf(), acknowledges = false)

        status.acknowledge()
        status.finish()

        assertTrue(reactions.isEmpty())
    }

    private fun recordingReactions(reactions: MutableList<List<String>>): TelegramClient =
        Proxy.newProxyInstance(
            TelegramClient::class.java.classLoader,
            arrayOf(TelegramClient::class.java),
        ) { _, method, args ->
            check(method.name == "executeAsync") { "unexpected client call: ${method.name}" }
            val request = assertIs<SetMessageReaction>(args.single())

            reactions += request.reactionTypes.orEmpty().map { assertIs<ReactionTypeEmoji>(it).emoji }
            CompletableFuture.completedFuture(true)
        } as TelegramClient

    private fun recordingChat(calls: MutableList<String>): TelegramClient {
        var nextId = 0

        return Proxy.newProxyInstance(
            TelegramClient::class.java.classLoader,
            arrayOf(TelegramClient::class.java),
        ) { _, method, args ->
            check(method.name == "executeAsync") { "unexpected client call: ${method.name}" }

            when (val request = args.single()) {
                is SendMessage -> {
                    nextId += 1
                    calls += "send #$nextId [${request.text}] button=${request.replyMarkup != null}"
                    CompletableFuture.completedFuture(Message().apply { messageId = nextId })
                }

                is EditMessageText -> {
                    calls += "edit #${request.messageId} [${request.text}] button=${request.replyMarkup != null}"
                    CompletableFuture.completedFuture(true)
                }

                is DeleteMessage -> {
                    calls += "delete #${request.messageId}"
                    CompletableFuture.completedFuture(true)
                }

                else -> error("unexpected request: $request")
            }
        } as TelegramClient
    }

    private fun statusOver(
        client: TelegramClient,
        posted: MutableList<String>,
        acknowledges: Boolean = false,
        activityOpensIt: Boolean = true,
    ) =
        TurnStatus(
            client = client,
            target = ChatTarget(-7),
            ownerId = 2,
            replyToMessageId = 77,
            messages = Messages.of(Language.ENGLISH),
            activityOpensIt = activityOpensIt,
            acknowledges = acknowledges,
            onPosted = { posted += it },
        )

    private fun chatAccepting(accepts: Boolean): TelegramClient =
        Proxy.newProxyInstance(
            TelegramClient::class.java.classLoader,
            arrayOf(TelegramClient::class.java),
        ) { _, method, args ->
            check(method.name == "executeAsync") { "unexpected client call: ${method.name}" }

            when {
                !accepts -> CompletableFuture.failedFuture<Any>(IllegalStateException("Bad Request: chat not found"))
                args.single() is SendMessage -> CompletableFuture.completedFuture(Message().apply { messageId = 1 })
                else -> CompletableFuture.completedFuture(true)
            }
        } as TelegramClient

    @Test
    fun `nothing to say yields no message`() {
        assertNull(statusMessageText(plan = null, label = null))
    }

    // the emoji is read first in a chat full of text, so a shared or missing picture says less than the
    // words.
    @Test
    fun `every activity is named behind an emoji of its own`() {
        val messages = Messages.of(Language.ENGLISH)

        val emojis =
            ToolActivity.entries.map { activity ->
                val line = activityStatusLabel(activity, messages)
                val emoji = line.substringBefore(' ')

                assertTrue(emoji.isNotBlank() && emoji.none { it.isLetterOrDigit() }, "$activity opens with no emoji: $line")
                assertEquals("$emoji ${messages.progressLabel(activity)}", line)

                emoji
            }

        assertEquals(emojis.size, emojis.toSet().size, "two activities share an emoji")
    }

    // the picture belongs to the activity, not the language.
    @Test
    fun `the same activity carries the same emoji in every language`() {
        assertEquals("🎨 Drawing", activityStatusLabel(ToolActivity.DRAWING, Messages.of(Language.ENGLISH)))
        assertEquals("🎨 Малюю", activityStatusLabel(ToolActivity.DRAWING, Messages.of(Language.UKRAINIAN)))
    }
}
