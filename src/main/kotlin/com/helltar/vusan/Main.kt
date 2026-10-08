package com.helltar.vusan

import com.helltar.vusan.agent.AgentFactory
import com.helltar.vusan.agent.AgentRunner
import com.helltar.vusan.agent.addressing.AmbientAddressing
import com.helltar.vusan.agent.addressing.LlmAddressingClassifier
import com.helltar.vusan.agent.ContextWindowPolicy
import com.helltar.vusan.agent.DEFAULT_PERSONALITY
import com.helltar.vusan.agent.conversation.ConversationRepository
import com.helltar.vusan.agent.conversation.LlmConversationCompactor
import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.agent.grouplog.LlmGroupLogDigester
import com.helltar.vusan.agent.memory.MemoryRepository
import com.helltar.vusan.agent.presence.Diary
import com.helltar.vusan.agent.presence.DiaryRepository
import com.helltar.vusan.agent.presence.Initiative
import com.helltar.vusan.agent.presence.LlmDiaryWriter
import com.helltar.vusan.agent.presence.LlmInitiativeMind
import com.helltar.vusan.agent.providerOutage
import com.helltar.vusan.config.*
import com.helltar.vusan.llm.FallbackInUse
import com.helltar.vusan.llm.FallbackLlmClient
import com.helltar.vusan.llm.LlmClient
import com.helltar.vusan.infra.Db
import com.helltar.vusan.infra.Http
import com.helltar.vusan.infra.Maintenance
import com.helltar.vusan.infra.createPublicHttpClient
import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.stt.OpenAiWhisperClient
import com.helltar.vusan.tasks.TaskScheduler
import com.helltar.vusan.tasks.TasksRepository
import com.helltar.vusan.telegram.ChatProfiles
import com.helltar.vusan.telegram.PollRegistry
import com.helltar.vusan.telegram.TelegramBotRunner
import com.helltar.vusan.telegram.addressingNames
import com.helltar.vusan.telegram.botProfile
import com.helltar.vusan.telegram.profilePhotoReference
import com.helltar.vusan.telegram.callback.InlineChoiceHandler
import com.helltar.vusan.telegram.callback.TaskMenuHandler
import com.helltar.vusan.telegram.delivery.TelegramDelivery
import com.helltar.vusan.telegram.inbound.VoiceTranscriber
import com.helltar.vusan.tools.ToolRegistryFactory
import com.helltar.vusan.tools.imagegen.resolveSelfImage
import com.helltar.vusan.telegram.tools.TelegramToolSets
import com.helltar.vusan.telegram.tools.sticker.StickerCatalog
import com.helltar.vusan.tools.vision.ImageVisionClient
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient

private val log = KotlinLogging.logger {}

// how much one maintenance pass takes on: enough that a neglected database catches up over a few
// rounds, small enough that a pass never holds the write lock against the turns being served.
private const val MAINTENANCE_BATCH = 100

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

        // signing in is the codex CLI's job, so the only thing left at startup is to fail loudly when
        // nobody has run `codex login` here, then validate the selected model and its capabilities
        // before the first user turn hits an opaque backend error. the reported client version goes in
        // first, because it decides how much of the model catalog that validation is shown.
        // the subscription may be the primary, the fallback or a role's; whichever it is, it is the one signed-in account.
        val codexAuth =
            listOfNotNull(config.llmProvider, config.llmFallback, config.vision, config.addressing?.provider)
                .firstNotNullOfOrNull { it as? LlmProviderConfig.Codex }
                ?.let { codex ->
                    pinCodexClientVersion(codex.clientVersion)
                    CodexAuthStore(http, codex.authFile)
                }

        // every configured model is asked about at the vendor before the first turn, so a typo fails here
        val llm = resolveLlmRuntime(config.llmProvider.preflighted(http, codexAuth), codexAuth)

        // a second provider stands behind the first for when it is out — a spent subscription, above all,
        // or a key whose credit ran dry with the subscription behind it. it wraps the client so that
        // every call the bot makes is covered, and so a turn that runs into the limit finishes on the
        // fallback instead of ending in "come back later".
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

        // what the turn and its status ask to find out whether the primary is answering right now.
        val fallbackInUse: () -> FallbackInUse? = { fallbackClient?.fallbackInUse }
        val client: LlmClient = fallbackClient ?: llm.client
        chatClient = client
        val vision = resolveVisionRuntime(config.vision?.preflighted(http, codexAuth), llm.copy(client = client), codexAuth)

        // a vision model of its own comes with a second client to close; otherwise vision rides on the chat one
        visionClient = vision?.takeIf { it.ownClient }?.client

        val telegramClient = OkHttpTelegramClient(config.telegramBotToken)

        // read once and shared: the runner matches mentions against it, the agent is told its own handle.
        val botProfile = telegramClient.botProfile()

        // the classifier's context and the bot's own recent lines both come from the group transcript, so
        // without one there is nothing to judge a message against and the feature stays off.
        val ambient =
            config.addressing?.let { addressing ->
                val transcript = groupLog ?: return@let null
                val runtime = resolveAddressingRuntime(addressing.copy(provider = addressing.provider.preflighted(http, codexAuth)), codexAuth)
                addressingClient = runtime.client

                AmbientAddressing(
                    LlmAddressingClassifier(runtime.client, runtime.model, runtime.options),
                    transcript,
                    botProfile.addressingNames(addressing.names),
                )
            }

        // a picture of the bot itself has to show the same face every time, which text-to-image cannot
        // hold on its own — so the reference is read once, here, and only where it can be used at all:
        // the self-portrait it edits, and the round video message it puts in the circle.
        val selfImage =
            if (config.openAiImage != null || config.elevenLabsApiKey != null)
                resolveSelfImage(config.selfImageFile, config.appearance) {
                    telegramClient.profilePhotoReference(botProfile.userId)
                }
            else null

        // the catalog only ever holds stickers vision has looked at, so without vision there is nothing
        // to learn and nothing to offer the model. with vision it learns on its own, and every set it
        // learns is paid for in vision calls, which is what the switch is for.
        val stickerCatalog =
            vision
                ?.takeIf { config.stickersEnabled }
                ?.let { StickerCatalog(telegramClient, ImageVisionClient(it.client, it.model)) }

        val contextWindowPolicy = ContextWindowPolicy(llm.model)
        val groupLogDigester = groupLog?.let { LlmGroupLogDigester(client, llm.model, llm.compactionOptions) }

        val toolRegistryFactory =
            ToolRegistryFactory(
                http, publicHttp, TelegramToolSets(telegramClient, stickerCatalog), config, conversation, memory,
                tasks, vision, groupLog, groupLogDigester, contextWindowPolicy.liveToolResultMaxChars, codexAuth,
                selfImage,
            )

        val agentFactory =
            AgentFactory(
                client, llm.model, llm.chatOptions,
                config.personality, botProfile.username, botProfile.displayName,
                maxModelCalls = config.agentMaxModelCalls,
                contextWindowPolicy = contextWindowPolicy,
            )

        val conversationCompactor =
            LlmConversationCompactor(client, llm.model, llm.compactionOptions, contextWindowPolicy)

        // both read whole stretches of a group's transcript with nobody having asked, so each is a
        // switch of its own, and neither looks into a chat the allowlist no longer names.
        val personality = config.personality ?: DEFAULT_PERSONALITY
        val isAllowedChat = { chat: ChatRef -> config.accessPolicy.allows(chat, user = null) }

        val diary =
            groupLog?.takeIf { config.diaryEnabled }?.let {
                Diary(
                    LlmDiaryWriter(client, llm.model, llm.compactionOptions, personality),
                    DiaryRepository(), it, isAllowedChat,
                )
            }

        val agentRunner =
            AgentRunner(
                agentFactory, toolRegistryFactory, conversation, memory, conversationCompactor,
                config.chatHistory, stickerCatalog?.let { catalog -> catalog::indexBlockFor },
                groupLog, { fallbackInUse()?.model }, config.maxConcurrentTurns,
                diary = diary?.let { it::blockFor },
            )

        // answers to a poll are read back through the group transcript, so without one there is
        // nothing to record them into and no reason to remember the polls either.
        val polls = groupLog?.let { PollRegistry() }

        val delivery = TelegramDelivery(telegramClient, stickerCatalog?.let { it::recheckSetOf }, groupLog, polls)
        val voiceTranscriber = createVoiceTranscriber(http, config)
        val chatProfiles = ChatProfiles(telegramClient, botProfile.userId)
        val taskMenu = TaskMenuHandler(telegramClient, tasks, TasksRepository.MAX_TASKS_PER_USER)
        val inlineChoices = InlineChoiceHandler(telegramClient, conversation::revision)

        val scheduler =
            TaskScheduler(
                tasks, agentRunner, delivery, chatProfiles, config.accessPolicy,
            )

        val initiative =
            config.initiative?.let { initiativeConfig ->
                val transcript = groupLog ?: return@let null

                Initiative(
                    LlmInitiativeMind(client, llm.model, llm.compactionOptions, personality),
                    transcript, delivery, initiativeConfig, isAllowedChat,
                    isAnswering = agentRunner::hasTurnUnderWayIn,
                    diary = diary?.let { it::blockFor },
                )
            }

        val botRunner =
            TelegramBotRunner(
                telegramClient, config.telegramBotToken, delivery, agentRunner, taskMenu, inlineChoices, tasks,
                chatProfiles, config.accessPolicy, voiceTranscriber, botProfile, stickerCatalog,
                groupLog, polls, fallbackInUse, ambient,
            )

        // retention runs on a clock of its own rather than on whoever happens to write next: what needs
        // clearing out most is exactly what nobody is writing to any more. the steps are wired here
        // because one of them belongs to a messenger, and nothing under `infra/` may know that.
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
                    polls?.let { Maintenance.Step("expired polls") { it.pruneExpired() } },
                    diary?.let { Maintenance.Step("diary retention") { it.pruneExpired() } },
                ),
            )

        logStartup(config, llm, fallback, vision, ambient?.botNames, toolRegistryFactory.availableToolNames)
        logPresence(config, groupLogOn = groupLog != null)

        val botJob = botRunner.start(this)
        val schedulerJob = scheduler.launchIn(this)
        val stickerJob = stickerCatalog?.launchDescriptionWorker(this)
        val maintenanceJob = maintenance.launchIn(this)
        val diaryJob = diary?.launchIn(this)
        val initiativeJob = initiative?.launchIn(this)

        try {
            botJob.join()
        } finally {
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

// stamped into the jar manifest at build time and read back off any class from it. a classpath run
// (`./gradlew run`) has no manifest, so it says so instead of inventing a number.
private fun appVersion(): String = AppConfig::class.java.`package`?.implementationVersion ?: "dev"

private fun createVoiceTranscriber(http: HttpClient, config: AppConfig): VoiceTranscriber? {
    val sttConfig =
        config.openAiStt
            ?: run {
                log.warn { "OPENAI_STT_API_KEY not set — voice message transcription and video sound disabled" }
                return null
            }

    return VoiceTranscriber(OpenAiWhisperClient(http, sttConfig), sttConfig)
}

private fun logPresence(config: AppConfig, groupLogOn: Boolean) {
    val initiative = config.initiative

    if (!groupLogOn) {
        if (config.diaryEnabled || initiative != null) {
            log.warn { "Diary and initiative are off: both read the group log, and GROUP_LOG_ENABLED=false" }
        }

        return
    }

    if (config.diaryEnabled) {
        log.info { "Diary: on — each group's closed day is written up by the chat model" }
    } else {
        log.info { "Diary: off (DIARY_ENABLED=false)" }
    }

    if (initiative == null) {
        log.info { "Initiative: off (INITIATIVE_ENABLED=false)" }
    } else {
        log.info {
            "Initiative: interval=[${initiative.intervalMinutes}m] maxMessagesPerDay=[${initiative.maxMessagesPerDay}] " +
                    "quietHours=[${initiative.quietHours.from}-${initiative.quietHours.until}]"
        }
    }
}

// ordered as an operator reads it: which model, how much room it has, what it may spend, what it can
// see, what it draws with, where it writes, and what it can call.
private fun logStartup(
    config: AppConfig,
    llm: LlmRuntime,
    fallback: LlmRuntime?,
    vision: VisionRuntime?,
    ambientNames: List<String>?,
    toolNames: List<String>,
) {
    log.info {
        "LLM: provider=[${llm.providerLabel}] model=[${llm.model.id}]" +
                llm.reasoningEffort?.let { " reasoningEffort=[$it]" }.orEmpty() +
                llm.serviceTier?.let { " serviceTier=[$it]" }.orEmpty()
    }

    fallback?.let { log.info { "LLM fallback: provider=[${it.providerLabel}] model=[${it.model.id}]" } }

    // openai and anthropic models carry a window of their own; a codex catalog that could not be read or a
    // third-party server leave the policy on its conservative default
    if (config.llmProvider.contextWindowTokens == null && llm.model.contextWindowTokens == ContextWindowPolicy.DEFAULT_CONTEXT_WINDOW_TOKENS) {
        log.warn {
            "Model context size unknown: using conservative fallback " +
                    "[${ContextWindowPolicy.DEFAULT_CONTEXT_WINDOW_TOKENS}] — set LLM_CONTEXT_WINDOW_TOKENS for this model"
        }
    } else {
        log.info { "Model context window: tokens=${llm.model.contextWindowTokens}" }
    }

    if (vision != null) {
        log.info { "Vision: provider=[${vision.providerLabel}] model=[${vision.model.id}]" }
        if (!config.stickersEnabled) log.info { "Stickers: off (STICKERS_ENABLED=false)" }
    } else {
        log.warn {
            "Vision disabled: model=[${llm.model.id}] cannot read images — " +
                    "set VISION_MODEL to run vision on a model of its own"
        }
    }

    config.addressing?.let {
        if (ambientNames == null) {
            log.warn { "Ambient addressing off: it reads the group log, and GROUP_LOG_ENABLED=false" }
        } else {
            log.info { "Ambient addressing: model=[${it.provider.model}] names=[${ambientNames.joinToString(", ")}]" }
        }
    }

    config.openAiImage?.let {
        log.info { "Images: route=[${it.route.name.lowercase()}] model=[${it.model}] quality=[${it.quality}]" }
    }

    log.info { "Database: [${config.databasePath}]" }
    log.info { "Tools enabled (${toolNames.size}): [${toolNames.joinToString(", ")}]" }
}
