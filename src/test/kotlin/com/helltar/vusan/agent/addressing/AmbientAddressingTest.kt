package com.helltar.vusan.agent.addressing

import com.helltar.vusan.agent.grouplog.GroupLogEntry
import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.GroupLogConfig
import com.helltar.vusan.config.LlmProviderConfig
import com.helltar.vusan.infra.Db
import com.helltar.vusan.request.AccessPolicy
import com.helltar.vusan.request.testChat
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AmbientAddressingTest {

    private lateinit var tempDir: Path
    private lateinit var groupLog: GroupLogRepository

    private val now: Instant = Instant.parse("2026-09-24T12:00:00Z")

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-ambient-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
        groupLog = GroupLogRepository(GroupLogConfig())
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `a message naming the bot is put to the classifier and its yes is followed`() = runBlocking {
        val classifier = FakeClassifier(answer = true)

        assertTrue(addressing(classifier).isAddressed(candidate("robin, what do you make of this")))
        assertEquals(1, classifier.inputs.size)
    }

    @Test
    fun `a no from the classifier keeps the bot out`() = runBlocking {
        assertFalse(addressing(FakeClassifier(answer = false)).isAddressed(candidate("robin never said that")))
    }

    @Test
    fun `a message that neither names the bot nor follows it never leaves the machine`() = runBlocking {
        val classifier = FakeClassifier(answer = true)
        groupLog.record(person("7", "Alice", "anyone seen the keys", minutesAgo(1)))

        assertFalse(addressing(classifier).isAddressed(candidate("and why is that")))
        assertTrue(classifier.inputs.isEmpty())
    }

    @Test
    fun `a message right after the bot spoke is asked about without the name`() = runBlocking {
        val classifier = FakeClassifier(answer = true)
        groupLog.record(bot("the keys are on the shelf", minutesAgo(4)))

        assertTrue(addressing(classifier).isAddressed(candidate("and why is that")))
    }

    @Test
    fun `the follow-up window closes five minutes after the bot spoke`() = runBlocking {
        val classifier = FakeClassifier(answer = true)
        groupLog.record(bot("the keys are on the shelf", minutesAgo(6)))

        assertFalse(addressing(classifier).isAddressed(candidate("and why is that")))
        assertTrue(classifier.inputs.isEmpty())
    }

    @Test
    fun `a bot line the classifier would not be shown opens no follow-up`() = runBlocking {
        val classifier = FakeClassifier(answer = true)
        groupLog.record(bot("the keys are on the shelf", minutesAgo(4)))
        repeat(6) { groupLog.record(person("${10 + it}", "Alice", "line $it", minutesAgo(3).plusSeconds(it.toLong()))) }

        assertFalse(addressing(classifier).isAddressed(candidate("and why is that")))
        assertTrue(classifier.inputs.isEmpty())
    }

    @Test
    fun `an author still waiting on the bot is asked about, and the classifier is told so`() = runBlocking {
        val classifier = FakeClassifier(answer = true)

        assertTrue(addressing(classifier).isAddressed(candidate("how is it going", authorWaiting = true)))
        assertEquals("Bob", classifier.inputs.single().botBusyFor)
    }

    @Test
    fun `nobody is said to be waiting unless the author is`() = runBlocking {
        val classifier = FakeClassifier(answer = true)

        addressing(classifier).isAddressed(candidate("robin, hello"))

        assertNull(classifier.inputs.single().botBusyFor)
    }

    @Test
    fun `the context names the bot by its first name and leaves the message itself out`() = runBlocking {
        val classifier = FakeClassifier(answer = true)
        groupLog.record(person("7", "Alice", "anyone seen the keys", minutesAgo(2)))
        groupLog.record(bot("on the shelf", minutesAgo(1)))
        groupLog.record(person(MESSAGE_ID, "Bob", "robin thanks", now))

        addressing(classifier).isAddressed(candidate("robin thanks"))

        val input = classifier.inputs.single()
        assertEquals(listOf("Alice", "Robin"), input.recent.map { it.from })
        assertEquals(ChatLine("Bob", "robin thanks"), input.message)
    }

    @Test
    fun `lines older than ten minutes are not shown`() = runBlocking {
        val classifier = FakeClassifier(answer = true)
        groupLog.record(person("7", "Alice", "an old topic", minutesAgo(11)))

        addressing(classifier).isAddressed(candidate("robin, hello"))

        assertTrue(classifier.inputs.single().recent.isEmpty())
    }

    @Test
    fun `a classifier that fails or answers nothing readable keeps the bot out`() = runBlocking {
        assertFalse(addressing(FakeClassifier(failure = IllegalStateException("boom"))).isAddressed(candidate("robin, hi")))
        assertFalse(addressing(FakeClassifier(answer = null)).isAddressed(candidate("robin, hi")))
    }

    @Test
    fun `a chat over its rate is not asked about any more`() = runBlocking {
        val classifier = FakeClassifier(answer = true)
        val addressing = addressing(classifier)

        val verdicts = (1..25).map { addressing.isAddressed(candidate("robin, $it", messageId = "m$it")) }

        assertEquals(20, classifier.inputs.size)
        assertEquals(List(20) { true } + List(5) { false }, verdicts)
    }

    @Test
    fun `the name is matched at the start of a word in any case`() {
        val pattern = namePattern(listOf("Robin", "Élodie"))

        assertTrue(pattern.containsMatchIn("robin, look at this"))
        assertTrue(pattern.containsMatchIn("hey ROBIN"))
        assertTrue(pattern.containsMatchIn("ask robins opinion"))
        assertTrue(pattern.containsMatchIn("ÉLODIE are you there"))
        assertTrue(pattern.containsMatchIn("élodie?"))
    }

    @Test
    fun `the same letters inside a word or a username are not the name`() {
        val pattern = namePattern(listOf("Robin"))

        assertFalse(pattern.containsMatchIn("the barrobin was closed"))
        assertFalse(pattern.containsMatchIn("ping @robin_fan about it"))
        assertFalse(pattern.containsMatchIn("see file_robin.txt"))
    }

    @Test
    fun `the rate limit slides with time and is kept per chat`() {
        val limit = ChatRateLimit(max = 2, window = 60.seconds)
        val other = testChat(-200)

        assertTrue(limit.tryAcquire(CHAT, now))
        assertTrue(limit.tryAcquire(CHAT, now.plusSeconds(10)))
        assertFalse(limit.tryAcquire(CHAT, now.plusSeconds(20)))
        assertTrue(limit.tryAcquire(other, now.plusSeconds(20)))
        assertTrue(limit.tryAcquire(CHAT, now.plusSeconds(61)))
    }

    private fun minutesAgo(minutes: Long): Instant = now.minusSeconds(minutes * 60)

    private fun addressing(classifier: AddressingClassifier) =
        AmbientAddressing(classifier, groupLog, listOf("Robin", "robin"), clock = { now })

    private fun candidate(text: String, messageId: String = MESSAGE_ID, authorWaiting: Boolean = false) =
        AmbientCandidate(
            chat = CHAT,
            messageId = messageId,
            author = "Bob",
            text = text,
            authorWaiting = authorWaiting,
        )

    private fun person(messageId: String, name: String, text: String, at: Instant) =
        GroupLogEntry(
            chat = CHAT,
            messageId = messageId,
            kind = "text",
            sentAt = at,
            senderId = "7",
            senderName = name,
            text = text,
        )

    private fun bot(text: String, at: Instant) =
        GroupLogEntry(chat = CHAT, messageId = null, kind = GroupLogEntry.BOT_KIND, sentAt = at, text = text)

    private class FakeClassifier(
        private val answer: Boolean? = null,
        private val failure: Throwable? = null,
    ) : AddressingClassifier {

        val inputs = mutableListOf<AddressingInput>()

        override suspend fun isAddressed(input: AddressingInput): Boolean? {
            inputs += input
            failure?.let { throw it }

            return answer
        }
    }

    private fun testConfig(dbPath: String) =
        AppConfig(
            agentMaxModelCalls = 70,
            accessPolicy = AccessPolicy(),
            appearance = null,
            databasePath = dbPath,
            elevenLabsApiKey = null,
            elevenLabsTts = null,
            giphyApiKey = null,
            klipyApiKey = null,
            llmProvider =
                LlmProviderConfig.OpenAi(
                    apiKey = "test",
                    model = "test",
                    requestTimeout = 60.seconds,
                ),
            maxConcurrentTurns = 4,
            image = null,
            openAiStt = null,
            regolithToken = null,
            regolithUrl = null,
            searxngUrl = null,
            selfImageFile = null,
            personality = null,
            tavilyApiKey = null,
            telegramBotToken = "test",
            ytDlpCookiesFile = null,
        )

    private companion object {
        val CHAT = testChat(-100)
        const val MESSAGE_ID = "500"
    }
}
