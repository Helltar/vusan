package com.helltar.vusan.agent.presence

import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.config.GroupLogConfig
import com.helltar.vusan.config.InitiativeConfig
import com.helltar.vusan.config.QuietHours
import com.helltar.vusan.delivery.DeliveryOutcome
import com.helltar.vusan.delivery.Destination
import com.helltar.vusan.delivery.OutputDelivery
import com.helltar.vusan.delivery.TurnDelivery
import com.helltar.vusan.infra.Db
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.ChatRef
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InitiativeTest {

    private lateinit var tempDir: Path
    private lateinit var groupLog: GroupLogRepository

    // midday, so the default quiet hours are far away
    private var now: Instant = Instant.parse("2026-10-04T12:00:00Z")
    private var nextMessageId = 1

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-initiative-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
        groupLog = GroupLogRepository(GroupLogConfig())
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `a chat seen for the first time is not looked at before a pause has passed`() = runBlocking {
        val mind = FakeMind(InitiativeDecision.Say("the ferry is the better plan"))
        val initiative = initiative(mind)

        chatter()
        initiative.tick()

        assertTrue(mind.inputs.isEmpty())
    }

    @Test
    fun `a line of its own is sent once the chat has been looked at`() = runBlocking {
        val delivery = FakeDelivery()
        val mind = FakeMind(InitiativeDecision.Say("the ferry is the better plan"))

        lookWith(initiative(mind, delivery))

        assertEquals(1, mind.inputs.size)
        assertEquals(listOf(Sent(CHAT, BotOutput.Text("the ferry is the better plan"), anchor = null)), delivery.sent)
    }

    @Test
    fun `text is escaped for the markup delivery reads it as`() = runBlocking {
        val delivery = FakeDelivery()

        lookWith(initiative(FakeMind(InitiativeDecision.Say("ferry > bridge & always was")), delivery))

        assertEquals(BotOutput.Text("ferry &gt; bridge &amp; always was"), delivery.sent.single().output)
    }

    @Test
    fun `a reply hangs under the message its number stood for`() = runBlocking {
        val delivery = FakeDelivery()
        val mind = FakeMind(InitiativeDecision.Say("bring the tent then", replyTo = 2))

        val ids = lookWith(initiative(mind, delivery))

        assertEquals(ids[1], delivery.sent.single().anchor)
    }

    @Test
    fun `a reply to a number the look never showed is sent on its own`() = runBlocking {
        val delivery = FakeDelivery()

        lookWith(initiative(FakeMind(InitiativeDecision.Say("bring the tent then", replyTo = 40)), delivery))

        assertNull(delivery.sent.single().anchor)
    }

    @Test
    fun `a reaction lands on the pointed message in telegram's own spelling`() = runBlocking {
        val delivery = FakeDelivery()

        // the heart arrives with a variation selector, which telegram's reaction set does not carry
        val ids = lookWith(initiative(FakeMind(InitiativeDecision.React(target = 3, emoji = "❤️")), delivery))

        assertEquals(listOf(Sent(CHAT, BotOutput.Reaction(ids[2], "❤"), anchor = null)), delivery.sent)
    }

    @Test
    fun `an emoji outside telegram's reaction set is not sent`() = runBlocking {
        val delivery = FakeDelivery()

        lookWith(initiative(FakeMind(InitiativeDecision.React(target = 1, emoji = "🧀")), delivery))

        assertTrue(delivery.sent.isEmpty())
    }

    @Test
    fun `only lines people wrote since the last look carry a number`() = runBlocking {
        val mind = FakeMind(InitiativeDecision.Silent())

        lookWith(initiative(mind))

        val lines = mind.inputs.single().lines

        assertEquals(listOf(null, null, null, 1, 2, 3), lines.map { it.number })
        assertEquals(listOf(false, false, false, true, true, true), lines.map { it.fresh })
    }

    @Test
    fun `nothing is looked at during the quiet hours`() = runBlocking {
        val mind = FakeMind(InitiativeDecision.Silent())

        lookWith(initiative(mind, quietHours = QuietHours(from = 11, until = 14)))

        assertTrue(mind.inputs.isEmpty())
    }

    @Test
    fun `a couple of new messages are not worth a look`() = runBlocking {
        val mind = FakeMind(InitiativeDecision.Silent())

        lookWith(initiative(mind), messages = 2)

        assertTrue(mind.inputs.isEmpty())
    }

    @Test
    fun `its own recent line keeps it from talking over itself`() = runBlocking {
        val mind = FakeMind(InitiativeDecision.Silent())

        lookWith(initiative(mind)) { groupLog.record(bot("the ferry leaves at nine", now.minusSeconds(120))) }

        assertTrue(mind.inputs.isEmpty())
    }

    @Test
    fun `a spent day leaves only reactions open and drops a line anyway written`() = runBlocking {
        val delivery = FakeDelivery()
        val mind = FakeMind(InitiativeDecision.Say("the ferry is the better plan"))
        val initiative = initiative(mind, delivery, maxMessagesPerDay = 1)

        lookWith(initiative)
        lookAgain(initiative)

        assertEquals(listOf(true, false), mind.inputs.map { it.maySpeak })
        assertEquals(listOf(0, 1), mind.inputs.map { it.saidToday })
        assertEquals(1, delivery.sent.size)
    }

    @Test
    fun `a second line has to wait out the gap, while a reaction does not`() = runBlocking {
        val delivery = FakeDelivery()
        val mind = FakeMind(InitiativeDecision.Say("the ferry is the better plan"))
        val initiative = initiative(mind, delivery)

        lookWith(initiative)
        lookAgain(initiative)
        now = now.plusSeconds(90 * 60)
        lookAgain(initiative)

        assertEquals(listOf(true, false, true), mind.inputs.map { it.maySpeak })
        assertEquals(2, delivery.sent.size)
    }

    @Test
    fun `a skipped look comes back sooner than a whole pause`() = runBlocking {
        val mind = FakeMind(InitiativeDecision.Silent())
        val initiative = initiative(mind)

        lookWith(initiative, messages = 2)
        now = now.plusSeconds(RETRY_SECONDS)
        chatter(messages = 1)
        initiative.tick()

        assertEquals(1, mind.inputs.size)
    }

    @Test
    fun `shadow mode decides and sends nothing`() = runBlocking {
        val delivery = FakeDelivery()
        val mind = FakeMind(InitiativeDecision.Say("the ferry is the better plan"))

        lookWith(initiative(mind, delivery, shadow = true))

        assertEquals(1, mind.inputs.size)
        assertTrue(delivery.sent.isEmpty())
    }

    @Test
    fun `a chat the allowlist does not name is never looked at`() = runBlocking {
        val mind = FakeMind(InitiativeDecision.Silent())

        lookWith(initiative(mind, isAllowed = { false }))

        assertTrue(mind.inputs.isEmpty())
    }

    @Test
    fun `a chat that turned the bot away is left alone afterwards`() = runBlocking {
        val delivery = FakeDelivery(outcome = DeliveryOutcome.Unreachable)
        val mind = FakeMind(InitiativeDecision.Say("the ferry is the better plan"))
        val initiative = initiative(mind, delivery)

        lookWith(initiative)
        lookAgain(initiative)

        assertEquals(1, mind.inputs.size)
    }

    @Test
    fun `a decision that fails ends the look in silence`() = runBlocking {
        val delivery = FakeDelivery()

        lookWith(initiative(FakeMind(failure = IllegalStateException("provider is down")), delivery))

        assertTrue(delivery.sent.isEmpty())
    }

    @Test
    fun `someone who used to write here and stopped is named`() = runBlocking {
        val mind = FakeMind(InitiativeDecision.Silent())
        val fiveDaysAgo = now.minusSeconds(5 * 24 * 3600)

        repeat(20) { groupLog.record(person("c$it", "note $it", fiveDaysAgo.plusSeconds(it.toLong()), "Carol", "9")) }
        lookWith(initiative(mind))

        val quiet = mind.inputs.single().quietLately

        assertEquals(1, quiet.size)
        assertTrue(quiet.single().startsWith("Carol"), quiet.single())
        assertFalse(quiet.any { it.startsWith("Alice") })
    }

    // the first pass only notes the chat and draws its pause; the look comes with the pass after it
    private suspend fun lookWith(
        initiative: Initiative,
        messages: Int = 3,
        before: suspend () -> Unit = {},
    ): List<String> {
        chatter()
        initiative.tick()

        return lookAgain(initiative, messages, before)
    }

    private suspend fun lookAgain(
        initiative: Initiative,
        messages: Int = 3,
        before: suspend () -> Unit = {},
    ): List<String> {
        now = now.plusSeconds(LONGEST_PAUSE_SECONDS)

        before()
        val ids = chatter(messages)
        initiative.tick()

        return ids
    }

    private suspend fun chatter(messages: Int = 3): List<String> =
        (1..messages).map { index ->
            val id = "m${nextMessageId++}"

            groupLog.record(person(id, "plan $id", now.minusSeconds((messages - index).toLong())))
            id
        }

    private fun initiative(
        mind: InitiativeMind,
        delivery: OutputDelivery = FakeDelivery(),
        shadow: Boolean = false,
        maxMessagesPerDay: Int = 4,
        quietHours: QuietHours = QuietHours(from = 0, until = 0),
        isAllowed: (ChatRef) -> Boolean = { true },
    ) =
        Initiative(
            mind = mind,
            groupLog = groupLog,
            delivery = delivery,
            config = InitiativeConfig(shadow, INTERVAL_MINUTES, maxMessagesPerDay, quietHours),
            isAllowed = isAllowed,
            zone = ZoneOffset.UTC,
            clock = { now },
            random = Random(1),
        )

    private data class Sent(val chat: ChatRef, val output: BotOutput, val anchor: String?)

    private class FakeDelivery(private val outcome: DeliveryOutcome = DeliveryOutcome.Handled) : OutputDelivery {

        val sent = mutableListOf<Sent>()

        override suspend fun deliver(delivery: TurnDelivery): DeliveryOutcome = error("not an unprompted send")

        override suspend fun notify(destination: Destination, text: String): DeliveryOutcome =
            error("not an unprompted send")

        override suspend fun deliverUnprompted(
            destination: Destination,
            outputs: List<BotOutput>,
            anchorMessageId: String?,
        ): DeliveryOutcome {
            sent += Sent(destination.chat, outputs.single(), anchorMessageId)

            return outcome
        }
    }

    private class FakeMind(
        private val decision: InitiativeDecision? = null,
        private val failure: Throwable? = null,
    ) : InitiativeMind {

        val inputs = mutableListOf<InitiativeInput>()

        override suspend fun decide(input: InitiativeInput): InitiativeDecision? {
            inputs += input
            failure?.let { throw it }

            return decision
        }
    }

    private companion object {
        const val INTERVAL_MINUTES = 10

        // a pause is at most one and a half intervals
        const val LONGEST_PAUSE_SECONDS = INTERVAL_MINUTES * 90L + 1

        // a skipped look is retried within ten minutes
        const val RETRY_SECONDS = 10 * 60L + 1
    }
}
