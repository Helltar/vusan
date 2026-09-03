package com.helltar.vusan

import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import com.helltar.vusan.agent.AgentFactory
import com.helltar.vusan.agent.AgentRunner
import com.helltar.vusan.agent.ContextWindowPolicy
import com.helltar.vusan.agent.conversation.ConversationRepository
import com.helltar.vusan.agent.conversation.LlmConversationCompactor
import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.agent.grouplog.LlmGroupLogDigester
import com.helltar.vusan.agent.memory.MemoryRepository
import com.helltar.vusan.budget.TokenBudget
import com.helltar.vusan.config.*
import com.helltar.vusan.infra.Db
import com.helltar.vusan.infra.Http
import com.helltar.vusan.stt.OpenAiWhisperClient
import com.helltar.vusan.tasks.TaskScheduler
import com.helltar.vusan.tasks.TasksRepository
import com.helltar.vusan.telegram.ChatProfiles
import com.helltar.vusan.telegram.TelegramBotRunner
import com.helltar.vusan.telegram.botProfile
import com.helltar.vusan.telegram.callback.InlineChoiceHandler
import com.helltar.vusan.telegram.callback.TaskMenuHandler
import com.helltar.vusan.telegram.delivery.TelegramDelivery
import com.helltar.vusan.telegram.inbound.VoiceTranscriber
import com.helltar.vusan.tools.ToolRegistryFactory
import com.helltar.vusan.tools.imagegen.resolveSelfImage
import com.helltar.vusan.tools.sticker.StickerCatalog
import com.helltar.vusan.tools.vision.ImageVisionClient
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import kotlin.time.Duration.Companion.minutes

private val log = KotlinLogging.logger {}

suspend fun main() = coroutineScope {
    log.info { "Starting Vusan ${appVersion()}" }

    val config = AppConfig.fromEnv()

    var http: HttpClient? = null
    var executor: MultiLLMPromptExecutor? = null
    var visionExecutor: AutoCloseable? = null

    try {
        Db.connect(config)
        http = Http.createClient()

        val conversation = ConversationRepository()
        val memory = MemoryRepository(config.maxMemoryPerScope)
        val tasks = TasksRepository()
        val groupLog = GroupLogRepository(config.groupLog).takeIf { config.groupLog.enabled }

        // signing in is the codex CLI's job, so the only thing left at startup is to fail loudly when
        // nobody has run `codex login` here, then validate the selected model and its capabilities
        // before the first user turn hits an opaque backend error.
        val codexAuth =
            (config.llmProvider as? LlmProviderConfig.Codex)?.let { codex -> CodexAuthStore(http, codex.authFile) }

        val llm = resolveLlmRuntime(codexPreflight(config.llmProvider, http, codexAuth), codexAuth)
        executor = MultiLLMPromptExecutor(llm.model.provider to llm.client)

        // everything downstream talks to the metered executor, so every LLM call the bot makes — turns,
        // history recaps, group-log digests, vision on the chat model — is counted against one daily budget.
        val tokenBudget = TokenBudget(config.tokenBudget)
        val chatExecutor = tokenBudget.meter(executor)
        val vision = resolveVisionRuntime(config.openAiVision, llm, chatExecutor, config.llmProvider.requestTimeout)

        // a vision model of its own comes with a second executor to close; otherwise vision rides on the chat one
        visionExecutor = vision?.executor?.takeIf { it !== chatExecutor }

        val telegramClient = OkHttpTelegramClient(config.telegramBotToken)

        // read once and shared: the runner matches mentions against it, the agent is told its own handle.
        val botProfile = telegramClient.botProfile()

        // a picture of the bot itself has to show the same face every time, which text-to-image cannot
        // hold on its own — so the reference is read once, here, and only where it can be used at all.
        val selfImage =
            config.openAiImage?.let {
                resolveSelfImage(config.selfImageFile, config.appearance, telegramClient, botProfile.userId)
            }

        // the catalog only ever holds stickers vision has looked at, so without vision there is nothing
        // to learn and nothing to offer the model.
        val stickerCatalog = vision?.let { StickerCatalog(telegramClient, ImageVisionClient(it.executor, it.model)) }

        val contextWindowPolicy = ContextWindowPolicy(llm.model)
        val groupLogDigester = groupLog?.let { LlmGroupLogDigester(chatExecutor, llm.model, llm.compactionParams) }

        val toolRegistryFactory =
            ToolRegistryFactory(
                http, telegramClient, config, conversation, memory, tasks, stickerCatalog, vision,
                groupLog, groupLogDigester, contextWindowPolicy.liveToolResultMaxChars, codexAuth, selfImage
            )

        val agentFactory =
            AgentFactory(
                chatExecutor, toolRegistryFactory, llm.model, llm.chatParams,
                config.personality, botProfile.username, botProfile.displayName,
                maxIterations = config.agentMaxIterations,
                contextWindowPolicy = contextWindowPolicy
            )

        val conversationCompactor =
            LlmConversationCompactor(chatExecutor, llm.model, llm.compactionParams, contextWindowPolicy)

        val agentRunner =
            AgentRunner(
                agentFactory, conversation, memory, conversationCompactor,
                config.chatHistory, stickerCatalog, groupLog, config.groupLog, tokenBudget
            )

        val delivery = TelegramDelivery(telegramClient, stickerCatalog?.let { it::recheckSetOf }, groupLog)
        val voiceTranscriber = createVoiceTranscriber(http, config)
        val chatProfiles = ChatProfiles(telegramClient, botProfile.userId)
        val taskMenu = TaskMenuHandler(telegramClient, tasks, config.maxTasksPerUser)
        val inlineChoices = InlineChoiceHandler(telegramClient, conversation::revision)

        val scheduler =
            TaskScheduler(
                tasks, agentRunner, delivery, config.taskMaxLatenessMinutes.minutes, chatProfiles, tokenBudget,
                config.bannedIds
            )

        val botRunner =
            TelegramBotRunner(
                telegramClient, config.telegramBotToken, delivery, agentRunner, taskMenu, inlineChoices, tasks,
                chatProfiles, config.allowedIds, config.bannedIds, voiceTranscriber, botProfile, stickerCatalog,
                groupLog
            )

        logStartup(config, llm, vision, toolRegistryFactory.availableToolNames)

        val botJob = botRunner.start(this)
        val schedulerJob = scheduler.launchIn(this)
        val stickerJob = stickerCatalog?.launchDescriptionWorker(this)

        try {
            botJob.join()
        } finally {
            stickerJob?.cancelAndJoin()
            schedulerJob.cancelAndJoin()
        }
    } finally {
        visionExecutor?.close()
        executor?.close()
        http?.close()
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

/**
 * Prove the ChatGPT session works before the bot starts taking messages, then apply the context
 * window and capabilities advertised by the account's own model catalog.
 *
 * Reading the token here also forces a refresh on a stale `auth.json`, so a host that has been idle
 * for days fails at startup with a "run `codex login`" message instead of on someone's first turn.
 */
private suspend fun codexPreflight(
    config: LlmProviderConfig,
    http: HttpClient,
    auth: CodexAuthStore?
): LlmProviderConfig {
    if (auth == null || config !is LlmProviderConfig.Codex) return config

    val plan = auth.planType()

    log.info {
        "Codex: signed in to ChatGPT${plan?.let { " (plan=[$it])" }.orEmpty()} auth=[${config.authFile}]"
    }

    val discovered = verifyCodexModel(http, auth, config.model) ?: return config

    log.info { "Codex: model=[${discovered.id}] (${discovered.displayName})" }

    return applyCodexModelMetadata(config, discovered)
}

// ordered as an operator reads it: which model, how much room it has, what it may spend, what it can
// see, where it writes, and what it can call.
private fun logStartup(
    config: AppConfig,
    llm: LlmRuntime,
    vision: VisionRuntime?,
    toolNames: List<String>
) {
    log.info {
        "LLM: provider=[${llm.providerLabel}] model=[${llm.model.id}]" +
                llm.reasoningEffort?.let { " reasoningEffort=[${it.name.lowercase()}]" }.orEmpty() +
                llm.serviceTier?.let { " serviceTier=[${it.requestValue}]" }.orEmpty()
    }

    if (llm.model.contextLength == null) {
        log.warn {
            "Model context size unknown: using conservative fallback " +
                    "[${ContextWindowPolicy.DEFAULT_CONTEXT_WINDOW_TOKENS}] — set LLM_CONTEXT_WINDOW_TOKENS for this model"
        }
    } else {
        log.info { "Model context window: tokens=${llm.model.contextLength}" }
    }

    val tokenBudget = config.tokenBudget

    if (tokenBudget.dailyTokens == null) {
        log.info { "Daily token budget: unlimited" }
    } else {
        log.info { "Daily token budget: tokens=${tokenBudget.dailyTokens} resetZone=[${tokenBudget.zone}]" }
    }

    if (vision != null) {
        log.info { "Vision: provider=[${vision.providerLabel}] model=[${vision.model.id}]" }
    } else {
        log.warn {
            "Vision disabled: model=[${llm.model.id}] cannot read images — " +
                    "set OPENAI_VISION_API_KEY to run vision on a separate model"
        }
    }

    log.info { "Database: [${config.databasePath}]" }
    log.info { "Tools enabled (${toolNames.size}): [${toolNames.joinToString(", ")}]" }
}
