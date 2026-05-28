package com.helltar.vusan

import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import com.helltar.vusan.agent.AgentFactory
import com.helltar.vusan.agent.AgentRunner
import com.helltar.vusan.agent.history.ChatHistoryRepository
import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.resolveLlmRuntime
import com.helltar.vusan.infra.Db
import com.helltar.vusan.infra.Http
import com.helltar.vusan.tasks.TaskScheduler
import com.helltar.vusan.tasks.TasksRepository
import com.helltar.vusan.stt.OpenAiWhisperClient
import com.helltar.vusan.telegram.TelegramBotRunner
import com.helltar.vusan.telegram.TelegramDelivery
import com.helltar.vusan.telegram.VoiceTranscriber
import com.helltar.vusan.tools.ToolRegistryFactory
import dev.inmo.tgbotapi.extensions.api.telegramBot
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import java.time.Duration

private val log = KotlinLogging.logger {}

fun main() = runBlocking {
    val config = AppConfig.fromEnv()

    Db.connect(config)

    val http = Http.createClient()
    val llm = resolveLlmRuntime(config.llmProvider)
    val executor = MultiLLMPromptExecutor(llm.koogProvider to llm.client)

    try {
        val history = ChatHistoryRepository()
        val tasks = TasksRepository()

        val toolRegistryFactory =
            ToolRegistryFactory(
                http = http,
                config = config,
                history = history,
                tasks = tasks,
                promptExecutor = executor,
                model = llm.model
            )

        val agentFactory =
            AgentFactory(
                promptExecutor = executor,
                toolRegistryFactory = toolRegistryFactory,
                model = llm.model,
                chatParams = llm.chatParams,
                persona = config.systemPrompt
            )

        val agentRunner =
            AgentRunner(
                agentFactory = agentFactory,
                history = history
            )

        val voiceTranscriber =
            config.openAiStt?.let { sttConfig ->
                VoiceTranscriber(OpenAiWhisperClient(http, sttConfig), sttConfig)
            } ?: run {
                log.warn { "OPENAI_STT_API_KEY not set — voice message transcription disabled" }
                null
            }

        val telegramBot = telegramBot(config.telegramBotToken)
        val delivery = TelegramDelivery(telegramBot)

        val botRunner =
            TelegramBotRunner(
                bot = telegramBot,
                delivery = delivery,
                agent = agentRunner,
                history = history,
                allowedIds = config.allowedIds,
                voiceTranscriber = voiceTranscriber
            )

        val scheduler =
            TaskScheduler(
                repo = tasks,
                agentRunner = agentRunner,
                delivery = delivery,
                history = history,
                pollInterval = Duration.ofSeconds(config.taskPollIntervalSeconds),
                maxLateness = Duration.ofMinutes(config.taskMaxLatenessMinutes),
            )

        log.info { "Starting Vusan: provider=[${llm.providerLabel}] model=[${llm.model.id}]" }
        val toolNames = toolRegistryFactory.availableToolNames
        log.info { "Tools enabled (${toolNames.size}): [${toolNames.joinToString(", ")}]" }

        val botJob = botRunner.start()
        val schedulerJob = scheduler.launchIn(this)

        try {
            botJob.join()
        } finally {
            schedulerJob.cancel()
        }
    } finally {
        executor.close()
        http.close()
        Db.disconnect()
    }
}
