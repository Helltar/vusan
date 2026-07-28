package com.helltar.vusan

import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import com.helltar.vusan.agent.AgentFactory
import com.helltar.vusan.agent.AgentRunner
import com.helltar.vusan.agent.history.ChatHistoryRepository
import com.helltar.vusan.agent.memory.MemoryRepository
import com.helltar.vusan.config.*
import com.helltar.vusan.infra.Db
import com.helltar.vusan.infra.Http
import com.helltar.vusan.stt.OpenAiWhisperClient
import com.helltar.vusan.tasks.TaskScheduler
import com.helltar.vusan.tasks.TasksRepository
import com.helltar.vusan.telegram.TaskMenuHandler
import com.helltar.vusan.telegram.TelegramBotRunner
import com.helltar.vusan.telegram.TelegramDelivery
import com.helltar.vusan.telegram.VoiceTranscriber
import com.helltar.vusan.tools.ToolRegistryFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import kotlin.time.Duration.Companion.minutes

private val log = KotlinLogging.logger {}

suspend fun main() = coroutineScope {
    val config = AppConfig.fromEnv()

    var http: HttpClient? = null
    var executor: MultiLLMPromptExecutor? = null
    var visionExecutor: AutoCloseable? = null

    try {
        Db.connect(config)
        http = Http.createClient()

        val history = ChatHistoryRepository()
        val memory = MemoryRepository(config.maxMemoryPerScope)
        val tasks = TasksRepository()

        val llm = resolveLlmRuntime(config.llmProvider)
        executor = MultiLLMPromptExecutor(llm.model.provider to llm.client)
        val vision = resolveVisionRuntime(config.openAiVision, llm, executor, config.llmProvider.requestTimeout)

        // a vision model of its own comes with a second executor to close; otherwise vision rides on the chat one
        visionExecutor = vision?.executor?.takeIf { it !== executor }

        val toolRegistryFactory = ToolRegistryFactory(http, config, history, memory, tasks, vision)
        val agentFactory = AgentFactory(executor, toolRegistryFactory, llm.model, llm.chatParams, config.personality)
        val agentRunner = AgentRunner(agentFactory, history, memory)

        val telegramClient = OkHttpTelegramClient(config.telegramBotToken)
        val delivery = TelegramDelivery(telegramClient)
        val voiceTranscriber = createVoiceTranscriber(http, config)
        val taskMenu = TaskMenuHandler(telegramClient, tasks, config.maxTasksPerUser)
        val botRunner =
            TelegramBotRunner(
                telegramClient,
                config.telegramBotToken,
                delivery,
                agentRunner,
                history,
                taskMenu,
                config.allowedIds,
                voiceTranscriber
            )
        val scheduler = TaskScheduler(tasks, agentRunner, delivery, history, config.taskMaxLatenessMinutes.minutes)

        logStartup(llm, vision, toolRegistryFactory.availableToolNames)

        val botJob = botRunner.start(this)
        val schedulerJob = scheduler.launchIn(this)

        try {
            botJob.join()
        } finally {
            schedulerJob.cancelAndJoin()
        }
    } finally {
        visionExecutor?.close()
        executor?.close()
        http?.close()
        Db.disconnect()
    }
}

private fun createVoiceTranscriber(http: HttpClient, config: AppConfig): VoiceTranscriber? {
    val sttConfig =
        config.openAiStt
            ?: run {
                log.warn { "OPENAI_STT_API_KEY not set — voice message transcription and video sound disabled" }
                return null
            }

    return VoiceTranscriber(OpenAiWhisperClient(http, sttConfig), sttConfig)
}

private fun logStartup(llm: LlmRuntime, vision: VisionRuntime?, toolNames: List<String>) {
    log.info { "Starting Vusan: provider=[${llm.providerLabel}] model=[${llm.model.id}]" }

    if (vision != null) {
        log.info { "Vision: provider=[${vision.providerLabel}] model=[${vision.model.id}]" }
    } else {
        log.warn {
            "Vision disabled: model=[${llm.model.id}] cannot read images — " +
                    "set OPENAI_VISION_API_KEY to run vision on a separate model"
        }
    }

    log.info { "Tools enabled (${toolNames.size}): [${toolNames.joinToString(", ")}]" }
}
