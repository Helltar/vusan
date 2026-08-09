package com.helltar.vusan.tools

import ai.koog.agents.core.tools.ToolRegistry
import com.helltar.vusan.agent.grouplog.GroupLogDigester
import com.helltar.vusan.agent.grouplog.GroupLogReader
import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.agent.conversation.ConversationRepository
import com.helltar.vusan.agent.memory.MemoryRepository
import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.VisionRuntime
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.stt.OpenAiWhisperClient
import com.helltar.vusan.tasks.TasksRepository
import com.helltar.vusan.tools.choice.InlineChoiceTools
import com.helltar.vusan.tools.currency.CurrencyTools
import com.helltar.vusan.tools.currency.ExchangeRateClient
import com.helltar.vusan.tools.files.FileDownloadClient
import com.helltar.vusan.tools.files.FileTools
import com.helltar.vusan.tools.giphy.GiphyClient
import com.helltar.vusan.tools.giphy.GiphyTools
import com.helltar.vusan.tools.grouplog.GroupLogTools
import com.helltar.vusan.tools.conversation.ConversationTools
import com.helltar.vusan.tools.imagegen.ImageGenTools
import com.helltar.vusan.config.CodexAuthStore
import com.helltar.vusan.config.ImageRoute
import com.helltar.vusan.tools.imagegen.ImageAuth
import com.helltar.vusan.tools.imagegen.OpenAiImageClient
import com.helltar.vusan.tools.images.ImageDownloadClient
import com.helltar.vusan.tools.memory.MemoryTools
import com.helltar.vusan.tools.message.MessageTools
import com.helltar.vusan.tools.poll.PollTools
import com.helltar.vusan.tools.quiz.QuizTools
import com.helltar.vusan.tools.reaction.ReactionTools
import com.helltar.vusan.tools.sandbox.SandboxClient
import com.helltar.vusan.tools.sandbox.SandboxTools
import com.helltar.vusan.tools.searxng.SearxngClient
import com.helltar.vusan.tools.searxng.SearxngTools
import com.helltar.vusan.tools.sticker.StickerCatalog
import com.helltar.vusan.tools.sticker.StickerTools
import com.helltar.vusan.tools.tasks.TaskTools
import com.helltar.vusan.tools.tavily.TavilyClient
import com.helltar.vusan.tools.tavily.TavilyTools
import com.helltar.vusan.tools.tgchannel.TelegramChannelClient
import com.helltar.vusan.tools.tgchannel.TelegramChannelImageDescriber
import com.helltar.vusan.tools.tgchannel.TelegramChannelTools
import com.helltar.vusan.tools.vision.ImageVisionClient
import com.helltar.vusan.tools.vision.VideoVisionClient
import com.helltar.vusan.tools.vision.VisionTools
import com.helltar.vusan.tools.vision.WhisperVideoAudioTranscriber
import com.helltar.vusan.tools.voice.ElevenLabsTtsClient
import com.helltar.vusan.tools.voice.VoiceTools
import com.helltar.vusan.tools.youtube.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import kotlin.time.Duration.Companion.seconds

class ToolRegistryFactory(
    http: HttpClient,
    private val config: AppConfig,
    private val conversation: ConversationRepository,
    private val memory: MemoryRepository,
    private val tasks: TasksRepository,
    private val stickers: StickerCatalog?,
    vision: VisionRuntime?,
    private val groupLog: GroupLogRepository?,
    groupLogDigester: GroupLogDigester?,
    toolResultMaxChars: Int,
    // present only on LLM_PROVIDER=codex; lets image generation ride the ChatGPT session
    // instead of needing a second paid key.
    codexAuth: CodexAuthStore? = null
) {

    private companion object {
        val TOOL_NAME_PROBE_CONTEXT = RequestContext(chatId = 0L, userId = 0L, messageId = 0L)
        val log = KotlinLogging.logger {}
    }

    val availableToolNames: List<String> by lazy {
        buildRegistry(TOOL_NAME_PROBE_CONTEXT, BotOutbox()).tools.map { it.name }.sorted()
    }

    // one chat log read may not eat the whole run's tool budget: the model still has to fit its own
    // answer, and a recap turn often calls other tools alongside it.
    private val groupLogReader =
        groupLog?.let {
            GroupLogReader(it, groupLogDigester, budgetChars = (toolResultMaxChars / 2).coerceIn(4_000, 24_000))
        }

    private val currency = CurrencyTools(ExchangeRateClient(http))
    private val fileDownloadClient = FileDownloadClient(http)
    private val imageDownloadClient = ImageDownloadClient(http)
    private val elevenLabsTts = config.elevenLabsTts
    private val openAiImage = config.openAiImage
    private val imageVisionClient = vision?.let { ImageVisionClient(it.executor, it.model) }
    private val telegramChannelClient = TelegramChannelClient(http)
    private val ytDlpRunner = YtDlpRunner(config.ytDlpCookiesFile)
    private val ytDlpClient = YtDlpClient(ytDlpRunner)
    private val youTubeTranscript = YouTubeTranscriptTools(YouTubeTranscriptClient(ytDlpRunner))

    private val telegramChannel =
        TelegramChannelTools(
            telegramChannelClient,
            vision?.let { TelegramChannelImageDescriber(it.executor, it.model) }
        )

    private val tavilyClient =
        optional("TAVILY_API_KEY", config.tavilyApiKey, "Tavily web search tool") {
            TavilyClient(http, it)
        }

    private val searxngClient =
        optional("SEARXNG_URL", config.searxngUrl, "SearXNG web/image search tools") {
            SearxngClient(http, it)
        }

    private val giphyClient =
        optional("GIPHY_API_KEY", config.giphyApiKey, "Giphy GIF tool") {
            GiphyClient(http, it)
        }

    private val elevenLabsTtsClient =
        optional("ELEVENLABS_API_KEY", config.elevenLabsApiKey, "voice/TTS tool") {
            ElevenLabsTtsClient(http, it)
        }

    private val openAiImageClient =
        when (openAiImage?.route) {
            ImageRoute.CODEX ->
                codexAuth?.let { OpenAiImageClient(http, ImageAuth.Codex(it)) }
                    ?: null.also { log.warn { "Codex auth unavailable — image generation/editing tools disabled" } }

            ImageRoute.PLATFORM, null ->
                optional("OPENAI_IMAGE_API_KEY", config.openAiImageApiKey, "image generation/editing tools") {
                    OpenAiImageClient(http, ImageAuth.ApiKey(it))
                }
        }

    private val sandboxClient =
        optional("SANDBOX_URL", config.sandboxUrl, "code execution tool") {
            SandboxClient(http, it, config.sandboxTimeoutSeconds.seconds)
        }

    // the key that enables voice transcription also hands a video's sound to the vision tool
    private val videoVisionClient =
        vision?.let {
            VideoVisionClient(
                promptExecutor = it.executor,
                model = it.model,
                transcriber =
                    config.openAiStt?.let { stt -> WhisperVideoAudioTranscriber(OpenAiWhisperClient(http, stt), stt) }
            )
        }

    /**
     * A tool the chat would refuse is left out rather than registered and rejected at delivery: producing
     * its output costs a download, an image generation, or a speech synthesis first, and the model cannot
     * spend any of that on a tool it was never offered. Text-first tools stay registered even when the
     * chat bans pictures — they still answer, just without the extras.
     */
    fun buildRegistry(context: RequestContext, outbox: BotOutbox): ToolRegistry {
        val chat = context.chatCapabilities

        return ToolRegistry {
            tools(MessageTools(outbox))
            tools(InlineChoiceTools(context, outbox, conversation::revision))
            tools(currency)
            tools(telegramChannel)
            tools(youTubeTranscript)
            tools(ConversationTools(conversation, context))
            tools(MemoryTools(memory, context))
            tools(TaskTools(repo = tasks, context, config.maxTasksPerUser, config.maxFollowUpsPerUser))

            if (chat.reactions) tools(ReactionTools(context, outbox))
            if (chat.audios) tools(YouTubeMusicTools(ytDlpClient, outbox))
            if (chat.videos) tools(YouTubeVideoTools(ytDlpClient, outbox))
            if (chat.documents) tools(FileTools(fileDownloadClient, outbox))

            if (chat.polls) {
                tools(QuizTools(outbox))
                tools(PollTools(outbox))
            }

            tavilyClient?.let { tools(TavilyTools(it, imageDownloadClient, outbox)) }
            searxngClient?.let { tools(SearxngTools(it, imageDownloadClient, outbox)) }
            sandboxClient?.let { tools(SandboxTools(it, outbox, context.attachedFile)) }

            if (chat.stickersAndAnimations) {
                giphyClient?.let { tools(GiphyTools(it, outbox)) }
                stickers?.let { tools(StickerTools(it, outbox)) }
            }

            if (groupLog != null && groupLogReader != null) {
                tools(GroupLogTools(groupLog, groupLogReader, context))
            }

            if (imageVisionClient != null && videoVisionClient != null) {
                tools(VisionTools(imageVisionClient, videoVisionClient, context.attachedFile))
            }

            if (chat.voiceNotes && elevenLabsTtsClient != null && elevenLabsTts != null) {
                tools(VoiceTools(elevenLabsTtsClient, elevenLabsTts, outbox))
            }

            if (chat.photos && openAiImageClient != null && openAiImage != null) {
                tools(ImageGenTools(openAiImageClient, openAiImage, outbox, context.attachedFile))
            }
        }
    }

    private fun <T> optional(envName: String, key: String?, toolDescription: String, build: (String) -> T): T? {
        if (key == null) {
            log.warn { "$envName not set — $toolDescription disabled" }
            return null
        }

        return build(key)
    }
}
