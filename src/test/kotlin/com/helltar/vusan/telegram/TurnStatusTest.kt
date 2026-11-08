package com.helltar.vusan.telegram

import com.helltar.vusan.agent.ToolActivity
import com.helltar.vusan.llm.FallbackInUse
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

    // until a status shows whether custom emoji go through, every icon goes up as a custom one
    private val icons = InterfaceIcons()
    private val running = icons.running(ToolActivity.RUNNING_CODE, "Running code", html = true)
    private val ranCode = icons.done("Running code", html = true)

    @Test
    fun `the running line sits under the plan`() {
        assertEquals("I will build the game\n\nRunning code", statusMessageText("I will build the game", emptyList(), "Running code"))
    }

    @Test
    fun `either half stands alone`() {
        assertEquals("Searching the web", statusMessageText(plan = null, steps = emptyList(), running = "Searching the web"))
        assertEquals("I will build the game", statusMessageText("I will build the game", emptyList(), running = null))
    }

    // a blank line keeps the running line apart from the quote above it
    @Test
    fun `finished steps fold into a quote between the plan and the running line`() {
        assertEquals(
            "I will build the game\n\n<blockquote expandable>✓ Searching the web\n✓ Reading a page</blockquote>\n\nRunning code",
            statusMessageText("I will build the game", listOf("✓ Searching the web", "✓ Reading a page"), "Running code"),
        )
    }

    @Test
    fun `without markup the steps are plain lines`() {
        assertEquals(
            "✓ Searching the web\n\nRunning code…",
            statusMessageText(plan = null, steps = listOf("✓ Searching the web"), running = "Running code…", html = false),
        )
    }

    @Test
    fun `a fallback note sits under the running line`() {
        assertEquals(
            "I will build the game\n\n\uD83D\uDCBB Running code\n\u21B3 on the fallback model: <code>gpt-5.4-mini</code>",
            statusMessageText(
                plan = "I will build the game",
                steps = emptyList(),
                running = "\uD83D\uDCBB Running code",
                fallbackNote = Messages.of(Language.ENGLISH).fallbackModelNote("gpt-5.4-mini", primaryBackIn = null),
            ),
        )

        // a turn with nothing named yet still says where it answers from
        assertEquals(
            "\u21B3 on the fallback model: <code>gpt-5.4-mini</code>",
            statusMessageText(
                plan = null,
                steps = emptyList(),
                running = null,
                fallbackNote = Messages.of(Language.ENGLISH).fallbackModelNote("gpt-5.4-mini", primaryBackIn = null),
            ),
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
                "edit #1 [I will build the game\n\n$running] button=true",
                "send #2 [halfway there] button=false",
                "edit #1 [I will build the game\n\n<blockquote expandable>$ranCode</blockquote>] button=false",
                "send #3 [$running] button=true",
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
                "edit #1 [I will build the game\n\n$running] button=true",
                "send #2 [halfway there] button=false",
                "edit #1 [I will build the game\n\n<blockquote expandable>$ranCode</blockquote>] button=false",
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
        fallbackInUse: () -> FallbackInUse? = { null },
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
            fallbackInUse = fallbackInUse,
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
        assertNull(statusMessageText(plan = null, steps = emptyList(), running = null))
    }

    // a planned turn keeps a record of what it did and how long it took; the steps go up as the turn moves on
    @Test
    fun `a planned turn ends as its plan over the folded steps and the time they took`() = runBlocking {
        val calls = mutableListOf<String>()

        statusOver(recordingChat(calls), posted = mutableListOf()).apply {
            say("I will compare the reviews")
            showActivity(ToolActivity.SEARCHING_WEB, mayOpen = true)
            showActivity(ToolActivity.READING_PAGE, mayOpen = true)
            finish()
        }

        val searched = icons.done("Searching the web", html = true)
        val reading = icons.running(ToolActivity.READING_PAGE, "Reading the page", html = true)

        assertEquals(
            "edit #1 [I will compare the reviews\n\n<blockquote expandable>$searched</blockquote>\n\n$reading] button=true",
            calls[2],
        )
        assertTrue(calls.last().startsWith("edit #1 [I will compare the reviews\n\n<blockquote expandable>Done in "), calls.last())
        assertTrue(calls.last().endsWith("$searched\n${icons.done("Reading the page", html = true)}</blockquote>] button=false"), calls.last())
    }

    // the usual model goes unnamed; a turn that ran on the fallback says which model answered
    @Test
    fun `a record names the fallback model the turn ran on`() = runBlocking {
        val calls = mutableListOf<String>()

        statusOver(recordingChat(calls), posted = mutableListOf(), fallbackInUse = { FallbackInUse("gpt-5.4-mini", primaryBackIn = null) }).apply {
            say("I will compare the reviews")
            showActivity(ToolActivity.SEARCHING_WEB, mayOpen = true)
            finish()
        }

        assertTrue(Regex("""Done in \d+ s · <code>gpt-5\.4-mini</code>\n""").containsMatchIn(calls.last()), calls.last())
    }

    // the emoji is read first in a chat full of text, so a shared or missing picture says less than the
    // words.
    @Test
    fun `every activity has an icon of its own`() {
        val plain = ToolActivity.entries.map { InterfaceIcons.activityFallback(it) }
        val custom = ToolActivity.entries.map { icons.running(it, "x", html = true) }

        assertEquals(plain.size, plain.toSet().size, "two activities share an emoji")
        assertEquals(custom.size, custom.toSet().size, "two activities share an animation")
    }

    // the picture belongs to the activity, not the language; without custom emoji it is the plain one, and the
    // ellipsis is what says the work goes on
    @Test
    fun `the same activity carries the same emoji in every language`() {
        assertEquals("🎨 Drawing…", icons.running(ToolActivity.DRAWING, Messages.of(Language.ENGLISH).progressLabel(ToolActivity.DRAWING), html = false))
        assertEquals("🎨 Малюю…", icons.running(ToolActivity.DRAWING, Messages.of(Language.UKRAINIAN).progressLabel(ToolActivity.DRAWING), html = false))
    }
}
