package com.helltar.vusan.tools

import com.helltar.vusan.agent.grouplog.GroupLogDigester
import com.helltar.vusan.agent.grouplog.GroupLogReader
import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.agent.conversation.ConversationRepository
import com.helltar.vusan.agent.TurnNarrator
import com.helltar.vusan.agent.TurnToolBudget
import com.helltar.vusan.agent.memory.MemoryRepository
import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.VisionRuntime
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.request.ChatContext
import com.helltar.vusan.request.Platform
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.request.SenderContext
import com.helltar.vusan.request.personKeyOrNull
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
import com.helltar.vusan.tools.context.ContextTools
import com.helltar.vusan.tools.conversation.ConversationTools
import com.helltar.vusan.tools.imagegen.ImageGenTools
import com.helltar.vusan.config.CodexAuthStore
import com.helltar.vusan.config.ImageRoute
import com.helltar.vusan.tools.imagegen.ImageAuth
import com.helltar.vusan.tools.imagegen.OpenAiImageClient
import com.helltar.vusan.tools.imagegen.SelfImage
import com.helltar.vusan.tools.images.ImageDownloadClient
import com.helltar.vusan.tools.memory.MemoryTools
import com.helltar.vusan.tools.message.MessageTools
import com.helltar.vusan.tools.poll.PollTools
import com.helltar.vusan.tools.quiz.QuizTools
import com.helltar.vusan.tools.reaction.ReactionTools
import com.helltar.vusan.tools.searxng.SearxngClient
import com.helltar.vusan.tools.searxng.SearxngTools
import com.helltar.vusan.tools.tasks.TaskTools
import com.helltar.vusan.tools.tavily.TavilyClient
import com.helltar.vusan.tools.tavily.TavilyTools
import com.helltar.vusan.tools.tgchannel.TelegramChannelClient
import com.helltar.vusan.tools.tgchannel.TelegramChannelImageDescriber
import com.helltar.vusan.tools.tgchannel.TelegramChannelReader
import com.helltar.vusan.tools.tgchannel.TelegramChannelTools
import com.helltar.vusan.tools.vision.ImageVisionClient
import com.helltar.vusan.tools.vision.VideoVisionClient
import com.helltar.vusan.tools.vision.VisionTools
import com.helltar.vusan.tools.vision.WhisperVideoAudioTranscriber
import com.helltar.vusan.tools.sites.SiteClient
import com.helltar.vusan.tools.sites.SiteTools
import com.helltar.vusan.tools.workspace.WorkspaceClient
import com.helltar.vusan.tools.workspace.WorkspaceTools
import com.helltar.vusan.tools.voice.ElevenLabsTtsClient
import com.helltar.vusan.tools.voice.VideoNoteTools
import com.helltar.vusan.tools.voice.VoiceTools
import com.helltar.vusan.tools.youtube.*
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import kotlin.time.Duration.Companion.seconds

class ToolRegistryFactory(
    http: HttpClient,
    publicHttp: HttpClient,
    // whatever the messenger this turn came from adds to the shared tools, built per turn
    private val platformTools: PlatformToolSets,
    private val config: AppConfig,
    private val conversation: ConversationRepository,
    private val memory: MemoryRepository,
    private val tasks: TasksRepository,
    vision: VisionRuntime?,
    private val groupLog: GroupLogRepository?,
    groupLogDigester: GroupLogDigester?,
    toolResultMaxChars: Int,
    // present only on LLM_PROVIDER=codex; lets image generation ride the ChatGPT session
    // instead of needing a second paid key.
    codexAuth: CodexAuthStore? = null,
    private val selfImage: SelfImage? = null
) {

    private companion object {
        // an ordinary person in an ordinary chat, because that is what the startup list is read as: what
        // this deployment can do. A sender that is not one person withholds exactly the tools whose
        // configuration an operator most wants confirmed.
        val TOOL_NAME_PROBE_CONTEXT =
            RequestContext(
                platform = Platform.TELEGRAM,
                chat = ChatContext(id = "1", isPrivate = true),
                sender = SenderContext(id = "1")
            )
        val log = KotlinLogging.logger {}
    }

    // what each conversation already loaded, so its next turn opens with the same tool array: that
    // array is part of the cached prompt prefix, and rebuilding it every turn costs more than the
    // schemas the catalog saves. See notes/tool-catalog.md.
    private val loadedGroups = LoadedToolGroups()

    val availableToolNames: List<String> by lazy {
        buildCatalog(TOOL_NAME_PROBE_CONTEXT, BotOutbox(), TurnToolBudget(0)).registry.tools.map { it.name }.sorted()
    }

    // one chat log read may not eat the whole run's tool budget: the model still has to fit its own
    // answer, and a recap turn often calls other tools alongside it.
    private val groupLogReader =
        groupLog?.let {
            GroupLogReader(it, groupLogDigester, budgetChars = (toolResultMaxChars / 2).coerceIn(4_000, 24_000))
        }

    private val currency = CurrencyTools(ExchangeRateClient(http))
    private val fileDownloadClient = FileDownloadClient(publicHttp)
    private val imageDownloadClient = ImageDownloadClient(fileDownloadClient)
    private val elevenLabsTts = config.elevenLabsTts
    private val openAiImage = config.openAiImage
    private val imageVisionClient = vision?.let { ImageVisionClient(it.executor, it.model) }
    private val telegramChannelClient = TelegramChannelClient(fileDownloadClient)
    private val ytDlpRunner = YtDlpRunner(config.ytDlpCookiesFile)
    private val ytDlpClient = YtDlpClient(ytDlpRunner)
    private val youTubeTranscript = YouTubeTranscriptTools(YouTubeTranscriptClient(ytDlpRunner))

    private val telegramChannel =
        TelegramChannelTools(
            TelegramChannelReader(
                telegramChannelClient,
                vision?.let { TelegramChannelImageDescriber(it.executor, it.model) }
            )
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

    // the round video message is the portrait plus the voice, so without a reference photo there is
    // nothing to put in the circle and the tool is left out rather than sending an empty one.
    private val selfPortrait =
        selfImage?.reference?.bytes
            ?: null.also {
                if (elevenLabsTtsClient != null)
                    log.warn { "No reference photo — the round video message tool is disabled" }
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

    private val workspaceClient =
        optional("WORKSPACE_URL", config.workspaceUrl, "workspace shell tools") {
            WorkspaceClient(http, it, config.workspaceMaxTimeoutSeconds.seconds, requireNotNull(config.workspaceToken))
        }

    // publishing means taking a snapshot of files the person built somewhere; without a workspace there
    // is nothing to snapshot, so the site host stays configured but unused rather than half-working.
    private val siteClient =
        optional("SITES_URL", config.sitesUrl, "site publishing tools") { url ->
            workspaceClient?.let { SiteClient(http, url, requireNotNull(config.sitesToken)) }
                ?: null.also { log.warn { "WORKSPACE_URL not set — site publishing has nothing to publish; tools disabled" } }
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
     *
     * A set registered with a [ToolGroup] is registered all the same; the group only decides whether its
     * schemas ride along in every request or wait for `loadTools`. Group what a turn rarely needs, and
     * leave visible what one may need without being asked for it by name.
     */
    fun buildCatalog(
        context: RequestContext,
        outbox: BotOutbox,
        toolBudget: TurnToolBudget,
        narrator: TurnNarrator? = null
    ): ToolCatalog {
        val chat = context.chat.capabilities

        return toolCatalog(
            preloaded = loadedGroups.of(context.scope),
            onLoad = { groups -> loadedGroups.remember(context.scope, groups) }
        ) {
            tools(MessageTools(outbox, narrator))
            tools(ContextTools(toolBudget))
            tools(InlineChoiceTools(context, outbox, conversation::revision))
            tools(ConversationTools(conversation, context))
            tools(MemoryTools(memory, context))
            tools(ToolGroup.CURRENCY, currency)
            tools(ToolGroup.TELEGRAM_CHANNELS, telegramChannel)
            tools(ToolGroup.YOUTUBE, youTubeTranscript)

            tools(
                ToolGroup.SCHEDULED_TASKS,
                TaskTools(repo = tasks, context, config.maxTasksPerUser, config.maxFollowUpsPerUser)
            )

            if (chat.reactions) tools(ReactionTools(context, outbox))
            if (chat.audios) tools(ToolGroup.YOUTUBE, YouTubeMusicTools(ytDlpClient, outbox))
            if (chat.videos) tools(ToolGroup.YOUTUBE, YouTubeVideoTools(ytDlpClient, outbox))
            if (chat.documents) tools(ToolGroup.FILE_TRANSFERS, FileTools(fileDownloadClient, outbox))

            if (chat.polls) {
                tools(ToolGroup.POLLS, QuizTools(outbox))
                tools(ToolGroup.POLLS, PollTools(outbox))
            }

            tavilyClient?.let { tools(TavilyTools(it, imageDownloadClient, outbox)) }
            searxngClient?.let { tools(SearxngTools(it, imageDownloadClient, outbox)) }
            workspaceClient?.let { client ->
                context.personKeyOrNull?.let { person ->
                    tools(WorkspaceTools(client, person, outbox, context.attachedFile))
                    siteClient?.let { tools(ToolGroup.WEB_PUBLISHING, SiteTools(it, client, person)) }
                }
            }

            if (chat.stickersAndAnimations) giphyClient?.let { tools(ToolGroup.GIFS, GiphyTools(it, outbox)) }

            if (groupLog != null && groupLogReader != null) {
                tools(GroupLogTools(groupLog, groupLogReader, context))
            }

            if (imageVisionClient != null && videoVisionClient != null) {
                tools(VisionTools(imageVisionClient, videoVisionClient, context.attachedFile))
            }

            if (elevenLabsTtsClient != null && elevenLabsTts != null) {
                if (chat.voiceNotes) tools(ToolGroup.VOICE_REPLIES, VoiceTools(elevenLabsTtsClient, elevenLabsTts, outbox))

                if (chat.videoNotes && selfPortrait != null)
                    tools(
                        ToolGroup.VOICE_REPLIES,
                        VideoNoteTools(elevenLabsTtsClient, elevenLabsTts, selfPortrait, outbox)
                    )
            }

            if (chat.photos && openAiImageClient != null && openAiImage != null) {
                tools(
                    ToolGroup.IMAGE_GENERATION,
                    ImageGenTools(openAiImageClient, openAiImage, outbox, context.attachedFiles, selfImage)
                )
            }

            platformTools.of(context, outbox).forEach { tools(it) }
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
