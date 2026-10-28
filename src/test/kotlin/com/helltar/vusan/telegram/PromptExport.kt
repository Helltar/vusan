package com.helltar.vusan.telegram

import com.helltar.vusan.agent.DEFAULT_PERSONALITY
import com.helltar.vusan.agent.TurnShelf
import com.helltar.vusan.agent.TurnToolBudget
import com.helltar.vusan.agent.conversation.ConversationRepository
import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.agent.memory.MemoryRepository
import com.helltar.vusan.agent.presence.DiaryRepository
import com.helltar.vusan.agent.presence.LeaveAloneRepository
import com.helltar.vusan.agent.systemPromptFor
import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.ElevenLabsTtsConfig
import com.helltar.vusan.config.GroupLogConfig
import com.helltar.vusan.config.ImageProviderConfig
import com.helltar.vusan.config.OpenAiSttConfig
import com.helltar.vusan.config.XaiVideoConfig
import com.helltar.vusan.config.llm.LlmProviderConfig
import com.helltar.vusan.config.llm.VisionRuntime
import com.helltar.vusan.llm.FakeLlmClient
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.codex.CodexAuthStore
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.request.AccessPolicy
import com.helltar.vusan.request.ChatContext
import com.helltar.vusan.request.Platform
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.request.SenderContext
import com.helltar.vusan.tasks.TasksRepository
import com.helltar.vusan.telegram.tools.TelegramToolSets
import com.helltar.vusan.telegram.tools.sticker.StickerCatalog
import com.helltar.vusan.tools.ToolCatalogFactory
import com.helltar.vusan.tools.ToolGroup
import com.helltar.vusan.tools.imagegen.SelfImage
import com.helltar.vusan.tools.imagegen.SourceImage
import com.helltar.vusan.tools.vision.ImageVisionClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import java.io.File
import java.lang.reflect.Proxy
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.telegram.telegrambots.meta.generics.TelegramClient

/**
 * The deployment a prompt is exported for. Everything optional is on unless switched off, since a study
 * of the prompt wants the tools there are; [sandbox], [images] and [videos] are what the prompt itself
 * says differently about.
 */
internal data class PromptExportOptions(
    val model: String = "gpt-6.1-sol",
    val personality: String = DEFAULT_PERSONALITY,
    val botUsernames: List<String> = emptyList(),
    val botDisplayName: String? = null,
    val sandbox: Boolean = true,
    val images: Boolean = true,
    val videos: Boolean = true,
    // the plan's researched answer, `answerFromWeb`, is there only where the chat runs on the ChatGPT plan
    val codexSearch: Boolean = true,
    val privateChat: Boolean = false,
)

/**
 * What a model is sent before any conversation — the system prompt and every tool definition, in request
 * order — as the production code builds them for one deployment. Nothing is called: every client behind
 * the tools is built over an engine that refuses to be used.
 *
 * `operational` is the content of the `<operational_contract>` block on its own, so a study can swap the
 * fixed contract at its head (everything before `# Passing results between tools`) and keep the generated
 * sections after it.
 */
internal fun exportPrompt(options: PromptExportOptions): JsonObject {
    val system =
        systemPromptFor(
            personality = options.personality,
            modelId = options.model,
            botUsernames = options.botUsernames,
            botDisplayName = options.botDisplayName,
            sandbox = options.sandbox,
            images = options.images,
            videos = options.videos,
        )

    val catalog = catalogFactory(options).buildCatalog(exportContext(options), BotOutbox(), TurnToolBudget(0), TurnShelf())
    val menu = catalog.menu()
    val definitions = catalog.requestDefinitions()
    val groupOf = ToolGroup.entries.flatMap { group -> catalog.load(listOf(group.groupName)).toolNames.map { it to group.groupName } }.toMap()

    return buildJsonObject {
        putJsonObject("deployment") {
            put("model", options.model)
            put("sandbox", options.sandbox)
            put("images", options.images)
            put("videos", options.videos)
            put("codexSearch", options.codexSearch)
            put("chat", if (options.privateChat) "private" else "group")
        }
        put("system", system)
        put("personality", options.personality.trim())
        put("operational", system.substringAfterLast("<operational_contract>\n").substringBeforeLast("\n</operational_contract>"))
        put("toolGroups", menu?.let(::JsonPrimitive) ?: JsonPrimitive(null as String?))
        putJsonArray("tools") {
            definitions.forEach { definition ->
                add(
                    buildJsonObject {
                        put("name", definition.name)
                        put("description", definition.description)
                        put("parameters", definition.parameters)
                        put("group", groupOf[definition.name])
                        put("readOnly", catalog.find(definition.name)?.readOnly == true)
                    },
                )
            }
        }
    }
}

/**
 * `./gradlew -q exportPrompt --args="--out /tmp/prompt.json"` — see [PromptExportOptions] for what the
 * flags switch: `--model <id>`, `--personality <file>`, `--username <handle>` (repeatable),
 * `--display-name <name>`, `--no-sandbox`, `--no-images`, `--no-videos`, `--no-codex-search`, `--private`.
 * Without `--out` the JSON goes to stdout, after whatever the log printed there.
 */
fun main(args: Array<String>) {
    val (options, out) = parseExportArgs(args.toList())
    val json = prettyJson.encodeToString(JsonObject.serializer(), exportPrompt(options))

    out?.writeText(json) ?: println(json)
}

private fun parseExportArgs(args: List<String>): Pair<PromptExportOptions, File?> {
    var options = PromptExportOptions()
    var out: File? = null
    val rest = args.toMutableList()

    fun value(flag: String): String = requireNotNull(rest.removeFirstOrNull()) { "$flag needs a value" }

    val flags: Map<String, (String) -> Unit> =
        mapOf(
            "--model" to { flag -> options = options.copy(model = value(flag)) },
            "--personality" to { flag -> options = options.copy(personality = File(value(flag)).readText()) },
            "--username" to { flag -> options = options.copy(botUsernames = options.botUsernames + value(flag)) },
            "--display-name" to { flag -> options = options.copy(botDisplayName = value(flag)) },
            "--no-sandbox" to { _ -> options = options.copy(sandbox = false) },
            "--no-images" to { _ -> options = options.copy(images = false) },
            "--no-videos" to { _ -> options = options.copy(videos = false) },
            "--no-codex-search" to { _ -> options = options.copy(codexSearch = false) },
            "--private" to { _ -> options = options.copy(privateChat = true) },
            "--out" to { flag -> out = File(value(flag)) },
        )

    while (rest.isNotEmpty()) {
        val flag = rest.removeFirst()
        requireNotNull(flags[flag]) { "unknown flag: $flag" }(flag)
    }

    return options to out
}

private fun exportContext(options: PromptExportOptions) =
    RequestContext(
        platform = Platform.TELEGRAM,
        chat = if (options.privateChat) ChatContext(id = "1", isPrivate = true) else ChatContext(id = "-100", isPrivate = false, type = "supergroup"),
        sender = SenderContext(id = "1"),
    )

private fun catalogFactory(options: PromptExportOptions): ToolCatalogFactory {
    val http = HttpClient(MockEngine { error("the prompt export makes no calls") })
    val vision = VisionRuntime("export", FakeLlmClient(), LlmModel(options.model, contextWindowTokens = 1_000_000), RequestOptions(), ownClient = false)
    val telegram = refusingTelegramClient()

    return ToolCatalogFactory(
        http = http,
        publicHttp = http,
        platformTools = TelegramToolSets(telegram, StickerCatalog(telegram, ImageVisionClient(vision.client, vision.model, vision.options))),
        config = exportConfig(options),
        conversation = ConversationRepository(),
        memory = MemoryRepository(),
        tasks = TasksRepository(),
        leaveAlone = LeaveAloneRepository(),
        vision = vision,
        groupLog = GroupLogRepository(GroupLogConfig()),
        diary = DiaryRepository(),
        toolResultMaxChars = 100_000,
        codexAuth = CodexAuthStore(http, Path("codex-auth-unused.json")),
        selfImage = SelfImage(SourceImage(ByteArray(1), "avatar.jpg", "image/jpeg"), appearance = "An appearance."),
    )
}

private fun exportConfig(options: PromptExportOptions) =
    AppConfig(
        agentMaxModelCalls = 100,
        accessPolicy = AccessPolicy(),
        appearance = null,
        databasePath = "unused.db",
        elevenLabsApiKey = KEY,
        elevenLabsTts = ElevenLabsTtsConfig(ElevenLabsTtsConfig.DEFAULT_MODEL, voiceId = "voice"),
        giphyApiKey = null,
        klipyApiKey = KEY,
        llmProvider =
            if (options.codexSearch) LlmProviderConfig.Codex(model = options.model, requestTimeout = 60.seconds)
            else LlmProviderConfig.OpenAi(apiKey = KEY, model = options.model, requestTimeout = 60.seconds),
        maxConcurrentTurns = 1,
        image = ImageProviderConfig.OpenAi(KEY).takeIf { options.images },
        xaiVideo = XaiVideoConfig(KEY).takeIf { options.videos },
        openAiStt = OpenAiSttConfig(KEY, OpenAiSttConfig.DEFAULT_MODEL),
        personality = null,
        regolithToken = KEY.takeIf { options.sandbox },
        regolithUrl = "http://127.0.0.1:1".takeIf { options.sandbox },
        searxngUrl = "http://127.0.0.1:1",
        selfImageFile = null,
        tavilyApiKey = KEY,
        telegramBotToken = KEY,
        ytDlpCookiesFile = null,
    )

private fun refusingTelegramClient(): TelegramClient =
    Proxy.newProxyInstance(TelegramClient::class.java.classLoader, arrayOf(TelegramClient::class.java)) { _, method, _ ->
        error("the prompt export makes no calls: ${method.name}")
    } as TelegramClient

private const val KEY = "export"

private val prettyJson = Json { prettyPrint = true }
