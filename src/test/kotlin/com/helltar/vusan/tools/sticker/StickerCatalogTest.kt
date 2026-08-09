package com.helltar.vusan.tools.sticker

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import com.helltar.vusan.budget.TokenBudgetExhaustedException
import com.helltar.vusan.budget.TokenBudgetStop
import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.HostedLlmProvider
import com.helltar.vusan.config.LlmProviderConfig
import com.helltar.vusan.infra.Db
import com.helltar.vusan.infra.tables.StickerSetsTable
import com.helltar.vusan.infra.tables.StickersTable
import com.helltar.vusan.tools.vision.FakePromptExecutor
import com.helltar.vusan.tools.vision.ImageVisionClient
import com.helltar.vusan.tools.vision.TEST_MODEL
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.api.methods.stickers.GetStickerSet
import org.telegram.telegrambots.meta.api.objects.ApiResponse
import org.telegram.telegrambots.meta.api.objects.File
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize
import org.telegram.telegrambots.meta.api.objects.stickers.Sticker
import org.telegram.telegrambots.meta.api.objects.stickers.StickerSet
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.io.ByteArrayInputStream
import java.io.Serializable
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

private const val CHAT = -100L
private const val OTHER_CHAT = -200L
private const val SET_NAME = "vusan_test_set"

// mirrors MIN_USES_BEFORE_LEARNING in the catalog
private const val MIN_USES_IN_TEST = 2

class StickerCatalogTest {

    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("vusan-sticker-catalog-test")
        runBlocking { Db.connect(testConfig(tempDir.resolve("vusan.db").toString())) }
    }

    @AfterTest
    fun tearDown() {
        runBlocking { Db.disconnect() }
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `a set the chat comes back to is pulled in whole and its stickers reach the index`() = runBlocking {
        val client = FakeStickerClient(setOf = listOf(sticker("a"), sticker("b")))
        val catalog = catalog(client, visionAnswer = "cat shrugging, unbothered")

        catalog.learn(sticker("a"))
        awaitDescriptionPass(catalog)

        val index = assertNotNull(catalog.indexBlockFor(CHAT))
        assertTrue(index.startsWith("<sticker_catalog>"), index)
        assertContains(index, "cat shrugging, unbothered")
        assertContains(index, "😂")
        assertEquals(2, index.lines().count { it.startsWith("#") })
    }

    @Test
    fun `a sticker vision refuses stays out of the index and out of the retry queue`() = runBlocking {
        val client = FakeStickerClient(setOf = listOf(sticker("a")))
        val catalog = catalog(client, visionAnswer = "I'm sorry, I can't help with that.")

        catalog.learn(sticker("a"))
        awaitDescriptionPass(catalog)

        assertNull(catalog.indexBlockFor(CHAT))

        val row = assertNotNull(storedStickers().singleOrNull())
        assertNull(row.description)

        // a refusal is final: the row must leave the queue instead of being asked about forever
        assertTrue(row.describeAttempts > 0, "refused sticker was left pending")
    }

    // a spent token budget says nothing about the sticker: counted as attempts, a budget that stays out for
    // hours would retire the whole backlog before vision gets another chance at it.
    @Test
    fun `a sticker left undescribed by a spent token budget keeps its retries`() = runBlocking {
        val client = FakeStickerClient(setOf = listOf(sticker("a")))
        val vision = BudgetStopExecutor()
        val catalog = StickerCatalog(client.proxy, ImageVisionClient(vision, TEST_MODEL))

        catalog.learn(sticker("a"))
        awaitWorker(catalog) { vision.callCount > 0 }

        val row = assertNotNull(storedStickers().singleOrNull())
        assertNull(row.description)
        assertEquals(0, row.describeAttempts, "the sticker was charged for the budget being out")
    }

    @Test
    fun `the index shows a chat only the sets its own people use`() = runBlocking {
        val client = FakeStickerClient(setOf = listOf(sticker("a")))
        val catalog = catalog(client, visionAnswer = "dog wagging tail")

        catalog.learn(sticker("a"))
        awaitDescriptionPass(catalog)

        assertNotNull(catalog.indexBlockFor(CHAT))
        assertNull(catalog.indexBlockFor(OTHER_CHAT))
    }

    @Test
    fun `custom emoji stickers are never learned`() = runBlocking {
        val client = FakeStickerClient(setOf = listOf(sticker("a")))
        val catalog = catalog(client, visionAnswer = "irrelevant")

        catalog.observe(CHAT, sticker("a", type = "custom_emoji"))

        assertTrue(storedStickers().isEmpty())
        assertNull(catalog.indexBlockFor(CHAT))
    }

    @Test
    fun `a deleted sticker set is dropped from the catalog`() = runBlocking {
        val client = FakeStickerClient(setOf = listOf(sticker("a"), sticker("b")))
        val catalog = catalog(client, visionAnswer = "penguin waving")

        catalog.learn(sticker("a"))
        awaitDescriptionPass(catalog)
        assertNotNull(catalog.indexBlockFor(CHAT))

        client.setGone = true
        backdateSetRefresh()
        awaitWorker(catalog) { storedStickers().isEmpty() }

        assertNull(catalog.indexBlockFor(CHAT))
    }

    @Test
    fun `a sticker removed from its set stops being offered`() = runBlocking {
        val client = FakeStickerClient(setOf = listOf(sticker("a"), sticker("b")))
        val catalog = catalog(client, visionAnswer = "penguin waving")

        catalog.learn(sticker("a"))
        awaitDescriptionPass(catalog)
        assertEquals(2, storedStickers().size)

        client.setOf = listOf(sticker("a"))
        backdateSetRefresh()
        awaitWorker(catalog) { storedStickers().size == 1 }

        // the survivor keeps the description already paid for
        val index = assertNotNull(catalog.indexBlockFor(CHAT))
        assertEquals(1, index.lines().count { it.startsWith("#") })
        assertContains(index, "penguin waving")
    }

    @Test
    fun `a set that cannot be reached right now is left alone`() = runBlocking {
        val client = FakeStickerClient(setOf = listOf(sticker("a")))
        val catalog = catalog(client, visionAnswer = "penguin waving")

        catalog.learn(sticker("a"))
        awaitDescriptionPass(catalog)

        client.failSet = true
        backdateSetRefresh()
        awaitWorker(catalog) { setRefreshedAt().isAfter(Instant.now().minusSeconds(3_600)) }

        assertEquals(1, storedStickers().size)
        assertContains(assertNotNull(catalog.indexBlockFor(CHAT)), "penguin waving")
    }

    @Test
    fun `every set the chat uses is represented in the index`() = runBlocking {
        val packs = listOf("pack_one", "pack_two", "pack_three")
        val client = FakeStickerClient(setOf = emptyList())
        val catalog = catalog(client, visionAnswer = "penguin waving")

        packs.forEach { pack ->
            client.setOf = (1..2).map { sticker("$pack-$it", set = pack) }
            catalog.learn(sticker("$pack-1", set = pack))
        }

        awaitDescriptionPass(catalog)

        val index = assertNotNull(catalog.indexBlockFor(CHAT))
        val shown = shownIds(index).map { setNameOf(it) }.toSet()

        // how the index is shared once it overflows is pinned by RoundRobinTest, which does not
        // have to wait on a vision pass per sticker
        assertEquals(packs.toSet(), shown)
    }

    @Test
    fun `a set used once and never again is not pulled in`() = runBlocking {
        val client = FakeStickerClient(setOf = (1..60).map { sticker("a$it") })
        val catalog = catalog(client, visionAnswer = "penguin waving")

        catalog.observe(CHAT, sticker("a1"))

        // a passing sticker from a set nobody reaches for again must not cost 60 vision calls
        assertTrue(storedStickers().isEmpty())
        assertNull(catalog.indexBlockFor(CHAT))
    }

    @Test
    fun `a chat cannot pull in more new sets than its daily budget`() = runBlocking {
        val client = FakeStickerClient(setOf = emptyList())
        val catalog = catalog(client, visionAnswer = "penguin waving")

        val withinBudget = listOf("pack_one", "pack_two", "pack_three")

        withinBudget.forEach { pack ->
            client.setOf = listOf(sticker("$pack-1", set = pack))
            catalog.learn(sticker("$pack-1", set = pack))
        }

        assertEquals(withinBudget.size, storedStickers().size)

        client.setOf = listOf(sticker("pack_four-1", set = "pack_four"))
        catalog.learn(sticker("pack_four-1", set = "pack_four"))

        assertEquals(withinBudget.size, storedStickers().size, "the budget did not stop a fourth set")
    }

    @Test
    fun `a set already known elsewhere costs a new chat nothing and needs no second use`() = runBlocking {
        val client = FakeStickerClient(setOf = listOf(sticker("a")))
        val catalog = catalog(client, visionAnswer = "penguin waving")

        catalog.learn(sticker("a"))
        awaitDescriptionPass(catalog)

        client.setGone = true
        catalog.observe(OTHER_CHAT, sticker("a"))

        // no fetch happens at all, so the set being unreachable now cannot matter
        assertContains(assertNotNull(catalog.indexBlockFor(OTHER_CHAT)), "penguin waving")
    }

    private fun shownIds(index: String): List<Long> =
        index.lines()
            .filter { it.startsWith("#") }
            .map { it.removePrefix("#").substringBefore(' ').toLong() }

    @Test
    fun `a rejected sticker gets its set re-read without waiting for the daily check`() = runBlocking {
        val client = FakeStickerClient(setOf = listOf(sticker("a")))
        val catalog = catalog(client, visionAnswer = "penguin waving")

        catalog.learn(sticker("a"))
        awaitDescriptionPass(catalog)

        val id = storedStickerIds().single()
        client.setGone = true

        // no backdating: the rejection itself is what has to make the set due
        catalog.recheckSetOf(id)
        awaitWorker(catalog) { storedStickers().isEmpty() }

        assertNull(catalog.indexBlockFor(CHAT))
    }

    // a set is only pulled in once the chat has reached for it more than once
    private suspend fun StickerCatalog.learn(sticker: Sticker, chatId: Long = CHAT) {
        repeat(MIN_USES_IN_TEST) { observe(chatId, sticker) }
    }

    private fun catalog(client: FakeStickerClient, visionAnswer: String) =
        StickerCatalog(client.proxy, ImageVisionClient(FakePromptExecutor(visionAnswer), TEST_MODEL))

    // the worker is the production entry point; one pass is done once no sticker is waiting on vision.
    private suspend fun awaitDescriptionPass(catalog: StickerCatalog) =
        awaitWorker(catalog) { storedStickers().none { it.description == null && it.describeAttempts == 0 } }

    private suspend fun awaitWorker(catalog: StickerCatalog, until: suspend () -> Boolean) = coroutineScope {
        val job = catalog.launchDescriptionWorker(this)

        try {
            withTimeout(10.seconds) {
                while (!until()) delay(20)
            }
        } finally {
            job.cancelAndJoin()
        }
    }

    private suspend fun backdateSetRefresh() = Db.dbTransaction {
        StickerSetsTable.update({ StickerSetsTable.name eq SET_NAME }) {
            it[refreshedAt] = Instant.now().minus(2, ChronoUnit.DAYS)
        }
        Unit
    }

    private suspend fun setRefreshedAt(): Instant = Db.dbTransaction {
        StickerSetsTable
            .select(StickerSetsTable.refreshedAt)
            .where { StickerSetsTable.name eq SET_NAME }
            .single()[StickerSetsTable.refreshedAt]
    }

    private data class StoredSticker(val description: String?, val describeAttempts: Int)

    private suspend fun storedStickerIds(): List<Long> =
        Db.dbTransaction { StickersTable.selectAll().map { it[StickersTable.id].value } }

    private suspend fun storedStickers(): List<StoredSticker> =
        Db.dbTransaction {
            StickersTable.selectAll().map {
                StoredSticker(it[StickersTable.description], it[StickersTable.describeAttempts])
            }
        }

    private suspend fun setNameOf(stickerId: Long): String =
        Db.dbTransaction {
            StickersTable
                .select(StickersTable.setName)
                .where { StickersTable.id eq stickerId }
                .single()[StickersTable.setName]
        }

    private fun sticker(id: String, type: String = "regular", set: String = SET_NAME): Sticker =
        Sticker.builder()
            .fileId("file-$id")
            .fileUniqueId("unique-$id")
            .type(type)
            .width(512)
            .height(512)
            .isAnimated(false)
            .isVideo(false)
            .emoji("😂")
            .setName(set)
            .thumbnail(PhotoSize.builder().fileId("thumb-$id").fileUniqueId("thumb-unique-$id").width(1).height(1).build())
            .build()

    private class BudgetStopExecutor : PromptExecutor() {

        var callCount = 0
            private set

        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            callCount++
            throw TokenBudgetExhaustedException(TokenBudgetStop.DayBudget(3.hours))
        }

        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
            error("executeStreaming not used in test")

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            error("moderate not used in test")

        override fun close() = Unit
    }

    private class FakeStickerClient(
        var setOf: List<Sticker>,
        var setGone: Boolean = false,
        var failSet: Boolean = false
    ) {

        val proxy: TelegramClient =
            Proxy.newProxyInstance(
                TelegramClient::class.java.classLoader,
                arrayOf(TelegramClient::class.java)
            ) { _, method, args ->
                when (method.name) {
                    "executeAsync" -> respond(args.single())
                    "downloadFileAsStream" -> ByteArrayInputStream(byteArrayOf(1, 2, 3))
                    else -> error("unexpected client call: ${method.name}")
                }
            } as TelegramClient

        private fun respond(request: Any): CompletableFuture<Any> =
            when (request) {
                is GetStickerSet ->
                    when {
                        setGone -> CompletableFuture.failedFuture(telegramError("Bad Request: STICKERSET_INVALID"))
                        failSet -> CompletableFuture.failedFuture(telegramError("Bad Gateway: upstream"))

                        else ->
                            CompletableFuture.completedFuture(
                                StickerSet().apply {
                                    name = request.name
                                    title = "Vusan test set"
                                    stickerType = "regular"
                                    stickers = setOf
                                }
                            )
                    }

                is GetFile ->
                    CompletableFuture.completedFuture(
                        File().apply {
                            fileId = request.fileId
                            fileUniqueId = "u"
                            filePath = "path"
                        }
                    )

                else -> error("unexpected request: ${request.javaClass.simpleName}")
            }

        private fun telegramError(description: String): TelegramApiRequestException =
            TelegramApiRequestException(
                "Error executing request",
                ApiResponse.builder<Serializable>()
                    .ok(false)
                    .errorCode(400)
                    .errorDescription(description)
                    .build()
            )
    }

    private fun testConfig(dbPath: String) =
        AppConfig(
            agentMaxIterations = 70,
            allowedIds = setOf(1L),
            databasePath = dbPath,
            elevenLabsApiKey = null,
            elevenLabsTts = null,
            giphyApiKey = null,
            llmProvider =
                LlmProviderConfig.Hosted(
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
            sandboxTimeoutSeconds = 60L,
            sandboxUrl = null,
            searxngUrl = null,
            personality = null,
            taskMaxLatenessMinutes = 60L,
            tavilyApiKey = null,
            telegramBotToken = "test",
            ytDlpCookiesFile = null
        )
}
