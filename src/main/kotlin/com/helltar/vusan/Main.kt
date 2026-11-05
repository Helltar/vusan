package com.helltar.vusan

import com.helltar.vusan.agent.AgentFactory
import com.helltar.vusan.agent.AgentRunner
import com.helltar.vusan.agent.ChatFloor
import com.helltar.vusan.agent.ContextWindowPolicy
import com.helltar.vusan.agent.DEFAULT_PERSONALITY
import com.helltar.vusan.agent.TurnSurroundings
import com.helltar.vusan.agent.addressing.AmbientAddressing
import com.helltar.vusan.agent.addressing.LlmAddressingClassifier
import com.helltar.vusan.agent.conversation.ConversationRepository
import com.helltar.vusan.agent.conversation.LlmConversationCompactor
import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.agent.memory.MemoryRepository
import com.helltar.vusan.agent.presence.Diary
import com.helltar.vusan.agent.presence.DiaryRepository
import com.helltar.vusan.agent.presence.Initiative
import com.helltar.vusan.agent.presence.LeaveAloneRepository
import com.helltar.vusan.agent.presence.LlmDiaryWriter
import com.helltar.vusan.agent.presence.LlmInitiativeMind
import com.helltar.vusan.agent.providerOutage
import com.helltar.vusan.config.*
import com.helltar.vusan.config.llm.*
import com.helltar.vusan.config.llm.LlmProviderConfig
import com.helltar.vusan.infra.Db
import com.helltar.vusan.infra.Http
import com.helltar.vusan.infra.Maintenance
import com.helltar.vusan.infra.createPublicHttpClient
import com.helltar.vusan.llm.FallbackInUse
import com.helltar.vusan.llm.FallbackLlmClient
import com.helltar.vusan.llm.LlmClient
import com.helltar.vusan.llm.codex.CodexAuthStore
import com.helltar.vusan.llm.codex.claimCodexClientVersion
import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.stt.OpenAiWhisperClient
import com.helltar.vusan.tasks.TaskScheduler
import com.helltar.vusan.tasks.TasksRepository
import com.helltar.vusan.telegram.ChatProfiles
import com.helltar.vusan.telegram.PollRegistry
import com.helltar.vusan.telegram.TelegramBotRunner
import com.helltar.vusan.telegram.addressingNames
import com.helltar.vusan.telegram.botProfile
import com.helltar.vusan.telegram.callback.InlineChoiceHandler
import com.helltar.vusan.telegram.callback.TaskMenuHandler
import com.helltar.vusan.telegram.delivery.TelegramDelivery
import com.helltar.vusan.telegram.inbound.VoiceTranscriber
import com.helltar.vusan.telegram.profilePhotoReference
import com.helltar.vusan.telegram.tools.TelegramToolSets
import com.helltar.vusan.telegram.tools.sticker.StickerCatalog
import com.helltar.vusan.tools.ToolCatalogFactory
import com.helltar.vusan.tools.imagegen.SelfImage
import com.helltar.vusan.tools.imagegen.resolveSelfImage
import com.helltar.vusan.tools.sandbox.SandboxServer
import com.helltar.vusan.tools.vision.ImageVisionClient
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

// enough that a neglected database catches up in a few rounds, small enough never to hold the write lock
// against live turns.
private const val MAINTENANCE_BATCH = 100

// okhttp's ten-second default is shorter than telegram takes to process an uploaded video, and a timeout
// after the upload says nothing about whether the message arrived, which it usually has.
private val TELEGRAM_IO_TIMEOUT = 60.seconds

suspend fun main() = coroutineScope {
    log.info { "Starting Vusan ${appVersion()}" }

    val config = AppConfig.fromEnv()

    var http: HttpClient? = null
    var publicHttp: HttpClient? = null
    var chatClient: LlmClient? = null
    var visionClient: LlmClient? = null
    var addressingClient: LlmClient? = null

    try {
        Db.connect(config)
        http = Http.createClient()
        publicHttp = createPublicHttpClient()

        val conversation = ConversationRepository()
        val memory = MemoryRepository()
        val tasks = TasksRepository()
        val groupLog = GroupLogRepository(config.groupLog).takeIf { config.groupLog.enabled }

        // signing in is the CLI's job, so startup only fails loudly when nobody ran `codex login`, then
        // validates the model before a first turn hits an opaque backend error. the claimed client version
        // goes first, since it decides how much of the catalog that validation sees. whatever role the
        // subscription plays, it is one signed-in account.
        val codexAuth =
            config.codexSignIn()?.let { signIn ->
                claimCodexClientVersion(http, signIn.clientVersion)
                CodexAuthStore(http, signIn.authFile)
            }

        // a plan that only draws has no model to preflight, so its sign-in is proven here, and it is named
        // beside the pictures in the startup summary
        val imagePlan = codexAuth?.takeIf { config.codexRoles().isEmpty() }?.planType()

        // every configured model is checked at the vendor before the first turn, so a typo fails here
        val llm = resolveLlmRuntime(config.llmProvider.preflighted(http, codexAuth), codexAuth)

        // a second provider for when the first is out (a spent subscription, a key out of credit). it wraps
        // the client so every call is covered and a turn that hits the limit finishes on the fallback instead
        // of "come back later".
        val fallback = config.llmFallback?.let { resolveLlmRuntime(it.preflighted(http, codexAuth), codexAuth) }
        val fallbackClient =
            fallback?.let {
                FallbackLlmClient(
                    primary = llm.client,
                    primaryLabel = llm.providerLabel,
                    fallback = it.client,
                    fallbackLabel = it.providerLabel,
                    fallbackModel = it.model,
                    fallbackOptions = it.chatOptions,
                    outageOf = { failure, now -> failure.providerOutage(now) },
                )
            }

        // whether the primary is answering right now, for the turn and its status.
        val fallbackInUse: () -> FallbackInUse? = { fallbackClient?.fallbackInUse }
        val client: LlmClient = fallbackClient ?: llm.client
        chatClient = client
        val vision = resolveVisionRuntime(config.vision?.preflighted(http, codexAuth), llm.copy(client = client), codexAuth)

        // a separate vision model brings a second client to close; otherwise vision rides on the chat one
        visionClient = vision?.takeIf { it.ownClient }?.client

        val telegramClient =
            OkHttpTelegramClient(
                OkHttpClient.Builder()
                    .writeTimeout(TELEGRAM_IO_TIMEOUT)
                    .readTimeout(TELEGRAM_IO_TIMEOUT)
                    .build(),
                config.telegramBotToken,
            )

        // read once: the runner matches mentions against it, and the agent is told its own handles.
        val botProfile = telegramClient.botProfile()

        // who asked not to be written to: read by the turn, the ambient gate and the initiative, written by
        // the turn that hears it
        val leaveAlone = LeaveAloneRepository()

        // the classifier's context and the bot's recent lines come from the group transcript, which the
        // config guarantees when the feature is on.
        val ambient =
            config.addressing?.let { addressing ->
                val transcript = checkNotNull(groupLog) { "answering without a mention needs the group log" }
                val runtime = resolveAddressingRuntime(addressing.copy(provider = addressing.provider.preflighted(http, codexAuth)), codexAuth)
                addressingClient = runtime.client

                AmbientAddressing(
                    LlmAddressingClassifier(runtime.client, runtime.model, runtime.options),
                    transcript,
                    botProfile.addressingNames(addressing.names),
                    leaveAlone,
                )
            }

        // text-to-image cannot hold one face across pictures, so the reference is read once, and only where
        // it is used: the self-portrait, the round video message and a video the bot is in.
        val selfImage =
            if (config.image != null || config.xaiVideo != null || config.elevenLabsApiKey != null)
                resolveSelfImage(config.selfImageFile, config.appearance) {
                    telegramClient.profilePhotoReference(botProfile.userId)
                }
            else null

        // the catalog holds only stickers vision described, so without vision there is nothing to learn or
        // offer. every learned set costs vision calls, which is what the switch is for.
        val stickerCatalog =
            vision
                ?.takeIf { config.stickersEnabled }
                ?.let { StickerCatalog(telegramClient, ImageVisionClient(it.client, it.model, it.options)) }

        // history is planned against the smaller window of the chat model and its fallback, since a mid-turn
        // fallback gets the same prompt
        val contextWindowTokens =
            listOfNotNull(llm.statedWindow(config.llmProvider), config.llmFallback?.let { fallback?.statedWindow(it) }).min()
        val contextWindowPolicy = ContextWindowPolicy(llm.model.copy(contextWindowTokens = contextWindowTokens))
        // a diary entry retells a group's talk, and one vendor's filter declining it is no reason to skip a
        // day while a fallback stands by
        val summaryOptions = llm.compactionOptions.copy(fallBackOnRefusal = true)

        // the diary reads the group log, so it exists only with one
        val diaryRepository = groupLog?.let { DiaryRepository() }

        // poll answers are read back through the group transcript; without one there is nothing to record
        // them into.
        val polls = groupLog?.let { PollRegistry() }

        val delivery =
            TelegramDelivery(
                telegramClient, stickerCatalog?.let { it::recheckSetOf }, groupLog, polls,
                onStickerSent = stickerCatalog?.let { it::recordSent },
            )
        val chatProfiles = ChatProfiles(telegramClient, botProfile.userId)

        val toolCatalogFactory =
            ToolCatalogFactory(
                http, publicHttp, TelegramToolSets(telegramClient, delivery, chatProfiles, stickerCatalog), config,
                conversation, memory, tasks, leaveAlone, vision, groupLog, diaryRepository,
                contextWindowPolicy.liveToolResultMaxChars, codexAuth, selfImage,
            )

        val agentFactory =
            AgentFactory(
                client, llm.model, llm.chatOptions,
                config.personality, botProfile.usernames, botProfile.displayName,
                sandbox = config.regolithUrl != null,
                images = config.image != null,
                videos = config.xaiVideo != null,
                maxModelCalls = config.agentMaxModelCalls,
                contextWindowPolicy = contextWindowPolicy,
            )

        val conversationCompactor =
            LlmConversationCompactor(client, llm.model, llm.compactionOptions, contextWindowPolicy)

        // both read a group's transcript unasked, so each has its own switch, and neither looks into a chat
        // the allowlist dropped.
        val personality = config.personality ?: DEFAULT_PERSONALITY
        val isAllowedChat = { chat: ChatRef -> config.accessPolicy.allows(chat, user = null) }

        val diary =
            if (groupLog != null && diaryRepository != null) {
                Diary(LlmDiaryWriter(client, llm.model, summaryOptions, personality), diaryRepository, groupLog, isAllowedChat)
            } else {
                null
            }

        // shared by the runner, which counts the answers, and the adapter, which reports the chat's other
        // lines
        val floor = ChatFloor(exempt = config.accessPolicy::isOwner)

        val agentRunner =
            AgentRunner(
                agentFactory, toolCatalogFactory, conversation, memory, conversationCompactor,
                config.chatHistory,
                TurnSurroundings(groupLog, diary?.let { it::blockFor }, stickerCatalog?.let { it::indexBlockFor }, leaveAlone),
                { fallbackInUse()?.model }, config.maxConcurrentTurns, floor,
            )

        val voiceTranscriber = createVoiceTranscriber(http, config)
        val taskMenu = TaskMenuHandler(telegramClient, tasks, TasksRepository.MAX_TASKS_PER_USER)
        val inlineChoices = InlineChoiceHandler(telegramClient, conversation::revision)

        val scheduler =
            TaskScheduler(
                tasks, agentRunner, delivery, chatProfiles, config.accessPolicy, leaveAlone,
            )

        val initiative =
            config.initiative?.let { initiativeConfig ->
                val transcript = groupLog ?: return@let null

                Initiative(
                    LlmInitiativeMind(client, llm.model, llm.compactionOptions, personality),
                    transcript, delivery, initiativeConfig, isAllowedChat,
                    isAnswering = agentRunner::hasTurnUnderWayIn,
                    diary = diary?.let { it::blockFor },
                    stickers = stickerCatalog,
                    leaveAlone = leaveAlone,
                )
            }

        val botRunner =
            TelegramBotRunner(
                telegramClient, config.telegramBotToken, delivery, agentRunner, taskMenu, inlineChoices, tasks,
                chatProfiles, config.accessPolicy, voiceTranscriber, botProfile, stickerCatalog,
                groupLog, polls, fallbackInUse, ambient, sandbox = config.regolithUrl != null, floor = floor,
            )

        // retention runs on its own clock, since what most needs clearing is what nobody writes to. the steps
        // are wired here because one belongs to a messenger, which `infra/` may not know.
        val maintenance =
            Maintenance(
                listOfNotNull(
                    Maintenance.Step("conversation retention") {
                        conversation.pruneExpired(
                            maxStoredInteractions = ConversationRepository.MAX_STORED_INTERACTIONS,
                            rawRetentionCutoff =
                                Instant.now().minus(config.chatHistory.retentionDays.toLong(), ChronoUnit.DAYS),
                            maxConversations = MAINTENANCE_BATCH,
                        )
                    },
                    groupLog?.let { Maintenance.Step("group log retention") { it.pruneExpired(MAINTENANCE_BATCH) } },
                    polls?.let { Maintenance.Step("expired polls") { it.pruneExpired(Instant.now()) } },
                    Maintenance.Step("leave-alone retention") { leaveAlone.pruneExpired(Instant.now()) },
                ),
            )

        logStartup(
            config, llm, fallback, vision, ambient?.botNames, toolCatalogFactory.availableToolNames, imagePlan,
            selfImage, toolCatalogFactory.sandboxServer(), groupLogOn = groupLog != null,
            contextWindowTokens = contextWindowTokens,
        )

        val botJob = botRunner.start(this)
        val schedulerJob = scheduler.launchIn(this)
        val stickerJob = stickerCatalog?.launchDescriptionWorker(this)
        val maintenanceJob = maintenance.launchIn(this)
        val diaryJob = diary?.launchIn(this)
        val initiativeJob = initiative?.launchIn(this)
        val recapJob = agentRunner.launchRecapsIn(this)

        try {
            botJob.join()
        } finally {
            recapJob.cancelAndJoin()
            initiativeJob?.cancelAndJoin()
            diaryJob?.cancelAndJoin()
            maintenanceJob.cancelAndJoin()
            stickerJob?.cancelAndJoin()
            schedulerJob.cancelAndJoin()
        }
    } finally {
        visionClient?.close()
        addressingClient?.close()
        chatClient?.close()
        http?.close()
        publicHttp?.close()
        Db.disconnect()
    }
}

// stamped into the jar manifest at build time; a classpath run (`./gradlew run`) has none and says so.
private fun appVersion(): String = AppConfig::class.java.`package`?.implementationVersion ?: "dev"

private fun createVoiceTranscriber(http: HttpClient, config: AppConfig): VoiceTranscriber? =
    config.openAiStt?.let { VoiceTranscriber(OpenAiWhisperClient(http, it), it) }

// a guessed window either starves the history or overruns the server, so an unstated one stops the startup
private fun LlmRuntime.statedWindow(config: LlmProviderConfig): Long =
    checkNotNull(model.contextWindowTokens) {
        "${config.envPrefix}_CONTEXT_WINDOW_TOKENS is required for ${model.id}: its server's model list states no " +
                "context window. Set it to the context the server runs the model with"
    }

// one line per capability, in the order an operator reads it. an unconfigured capability is
// `off (VAR not set)` at info, in the same shape as the ones that are on; warnings are for what is
// configured and still cannot work.
private fun logStartup(
    config: AppConfig,
    llm: LlmRuntime,
    fallback: LlmRuntime?,
    vision: VisionRuntime?,
    ambientNames: List<String>?,
    toolNames: List<String>,
    // the subscription plan behind the pictures, when it serves nothing else
    imagePlan: String?,
    // the self-image source, resolved only where something uses it
    selfImage: SelfImage?,
    // what the Regolith server said it runs, or null when none is configured or it did not answer
    sandboxServer: SandboxServer?,
    groupLogOn: Boolean,
    contextWindowTokens: Long,
) {
    log.info { "Owner: id=[${config.accessPolicy.owner}] allowed=[${config.accessPolicy.allowed.size}] banned=[${config.accessPolicy.banned.size}]" }

    when {
        config.personality != null -> log.info { "Personality: file=[${config.personalityFile}] chars=[${config.personality.length}]" }
        config.personalityFile != null -> log.warn { "Personality: built-in — PERSONALITY_FILE=[${config.personalityFile}] is blank" }
        else -> log.info { "Personality: built-in (PERSONALITY_FILE not set)" }
    }

    when {
        config.appearance != null -> log.info { "Appearance: file=[${config.appearanceFile}] chars=[${config.appearance.length}]" }
        config.appearanceFile != null -> log.warn { "Appearance: off — APPEARANCE_FILE=[${config.appearanceFile}] is blank" }
        else -> log.info { "Appearance: off (APPEARANCE_FILE not set)" }
    }

    log.info {
        "LLM: provider=[${llm.providerLabel}] model=[${llm.model.id}]" +
                llm.reasoningEffort?.let { " reasoningEffort=[$it]" }.orEmpty() +
                llm.serviceTier?.let { " serviceTier=[$it]" }.orEmpty()
    }

    log.info {
        fallback?.let { "LLM fallback: provider=[${it.providerLabel}] model=[${it.model.id}]" }
            ?: "LLM fallback: off (LLM_FALLBACK_PROVIDER not set)"
    }

    log.info {
        "Model context window: tokens=$contextWindowTokens" +
                " (fallback's, the smaller)".takeIf { contextWindowTokens != llm.model.contextWindowTokens }.orEmpty()
    }

    if (vision != null) {
        log.info { "Vision: provider=[${vision.providerLabel}] model=[${vision.model.id}]" }
    } else {
        log.warn { "Vision: off — model=[${llm.model.id}] cannot read images; set VISION_MODEL to run vision on a model of its own" }
    }

    log.info {
        when {
            !config.stickersEnabled -> "Stickers: off (STICKERS_ENABLED=false)"
            vision == null -> "Stickers: off — the catalog describes stickers through vision"
            else -> "Stickers: on"
        }
    }

    log.info {
        config.image?.let { image ->
            "Images: provider=[${image.name}]" + imagePlan?.let { " plan=[$it]" }.orEmpty() +
                    " model=[${image.model}] quality=[${image.quality}] gifs=[${if (image.drawsGifs) "on" else "off"}]"
        } ?: "Images: off (IMAGE_PROVIDER not set)"
    }

    log.info {
        config.xaiVideo?.let { video ->
            "Videos: provider=[xai] model=[${video.model}] resolution=[${video.resolution}] voice=[${video.voice ?: "the model's own"}]"
        } ?: "Videos: off (XAI_API_KEY not set)"
    }

    log.info {
        config.elevenLabsTts?.let { tts ->
            "Voice: provider=[elevenlabs] model=[${tts.model}] voice=[${tts.voiceId}] " +
                    "roundVideo=[${if (selfImage?.reference != null) "on" else "off — no reference photo"}]"
        } ?: "Voice: off (ELEVENLABS_API_KEY not set)"
    }

    // the face a picture of the bot is held to: the operator's file, else the platform avatar
    val reference = selfImage?.reference

    when {
        reference != null ->
            log.info {
                val source = config.selfImageFile?.let { "file=[$it]" } ?: "avatar=[${reference.filename}]"

                "Self-portrait: $source bytes=[${reference.bytes.size}]"
            }

        selfImage != null -> log.info { "Self-portrait: appearance only — no reference photo" }

        config.image != null || config.xaiVideo != null ->
            log.warn { "Self-portrait: off — no reference photo and no APPEARANCE_FILE; a picture or a video of the bot shows a different person every time" }

        else -> log.info { "Self-portrait: off — nothing draws or films it" }
    }

    log.info { config.openAiStt?.let { "Voice input: model=[${it.model}]" } ?: "Voice input: off (OPENAI_STT_API_KEY not set)" }

    val searches =
        buildList {
            if (config.tavilyApiKey != null) add("tavily")
            if (config.searxngUrl != null) add("searxng")
            // the plan answers searches when it is the chat provider or its fallback, as the catalog rules
            if (listOfNotNull(config.llmProvider, config.llmFallback).filterIsInstance<LlmProviderConfig.Codex>().any { it.webSearch }) add("codex")
        }

    log.info {
        if (searches.isEmpty()) "Web search: off (TAVILY_API_KEY and SEARXNG_URL not set)"
        else "Web search: providers=[${searches.joinToString(", ")}]"
    }

    log.info {
        when {
            config.klipyApiKey != null -> "GIFs: provider=[klipy]"
            config.giphyApiKey != null -> "GIFs: provider=[giphy]"
            else -> "GIFs: off (KLIPY_API_KEY and GIPHY_API_KEY not set)"
        }
    }

    when {
        config.regolithUrl == null -> log.info { "Sandbox: off (REGOLITH_URL not set)" }

        sandboxServer == null ->
            log.warn { "Sandbox: url=[${config.regolithUrl}] — the Regolith server did not answer; the shell tools fail until it does" }

        // the sdk decodes by its own protocol, so a server speaking another one fails mid-turn instead
        !sandboxServer.compatible ->
            log.warn {
                "Sandbox: url=[${config.regolithUrl}] server=[${sandboxServer.version}] — it speaks protocol " +
                        "${sandboxServer.protocol}, this build reads ${sandboxServer.expectedProtocol}; see docs/sandbox.md for the release to run"
            }

        else -> log.info {
            "Sandbox: url=[${config.regolithUrl}] server=[${sandboxServer.version}] publishing=[${if (sandboxServer.publishing) "on" else "off"}]"
        }
    }

    log.info { if (groupLogOn) "Group log: on retentionDays=[${config.groupLog.retentionDays}]" else "Group log: off (GROUP_LOG_ENABLED=false)" }

    log.info {
        config.addressing?.let { "Ambient addressing: model=[${it.provider.model}] names=[${ambientNames.orEmpty().joinToString(", ")}]" }
            ?: "Ambient addressing: off (ADDRESSING_ENABLED not true)"
    }

    if (!groupLogOn) {
        log.warn { "Diary and initiative: off — both read the group log, and GROUP_LOG_ENABLED=false" }
    } else {
        log.info { "Diary: on — each group's closed day is written up by the chat model" }

        log.info {
            config.initiative?.let {
                "Initiative: interval=[${it.intervalMinutes}m] maxMessagesPerDay=[${it.maxMessagesPerDay}] " +
                        "quietHours=[${it.quietHours.from}-${it.quietHours.until}]"
            } ?: "Initiative: off (INITIATIVE_ENABLED=false)"
        }
    }

    log.info { "Database: [${config.databasePath}]" }
    log.info { "Tools enabled (${toolNames.size}): [${toolNames.joinToString(", ")}]" }
}
