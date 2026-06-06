package com.helltar.vusan

import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import com.helltar.vusan.agent.AgentFactory
import com.helltar.vusan.agent.AgentRunner
import com.helltar.vusan.agent.history.ChatHistoryRepository
import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.resolveLlmRuntime
import com.helltar.vusan.infra.Db
import com.helltar.vusan.infra.Http
import com.helltar.vusan.stt.OpenAiWhisperClient
import com.helltar.vusan.tasks.TaskScheduler
import com.helltar.vusan.tasks.TasksRepository
import com.helltar.vusan.telegram.TelegramBotRunner
import com.helltar.vusan.telegram.TelegramDelivery
import com.helltar.vusan.telegram.VoiceTranscriber
import com.helltar.vusan.tools.ToolRegistryFactory
import dev.inmo.tgbotapi.extensions.api.telegramBot
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

suspend fun main() = coroutineScope {
    val config = AppConfig.fromEnv()

    var http: HttpClient? = null
    var executor: MultiLLMPromptExecutor? = null

    try {
        Db.connect(config)

        http = Http.createClient()
        val llm = resolveLlmRuntime(config.llmProvider)
        executor = MultiLLMPromptExecutor(llm.koogProvider to llm.client)

        val history = ChatHistoryRepository()
        val tasks = TasksRepository()

        val toolRegistryFactory = ToolRegistryFactory(http, config, history, tasks, executor, llm.model)
        val agentFactory = AgentFactory(executor, toolRegistryFactory, llm.model, llm.chatParams, config.systemPrompt)
        val agentRunner = AgentRunner(agentFactory, history)

        val voiceTranscriber =
            config.openAiStt?.let { sttConfig ->
                VoiceTranscriber(OpenAiWhisperClient(http, sttConfig), sttConfig)
            } ?: run {
                log.warn { "OPENAI_STT_API_KEY not set — voice message transcription disabled" }
                null
            }

        val bot = telegramBot(config.telegramBotToken)
        val delivery = TelegramDelivery(bot)
        val botRunner = TelegramBotRunner(bot, delivery, agentRunner, history, config.allowedIds, voiceTranscriber)

        val pollInterval = config.taskPollIntervalSeconds.seconds
        val maxLateness = config.taskMaxLatenessMinutes.minutes
        val scheduler = TaskScheduler(tasks, agentRunner, delivery, history, pollInterval, maxLateness)

        log.info { "Starting Vusan: provider=[${llm.providerLabel}] model=[${llm.model.id}]" }
        val toolNames = toolRegistryFactory.availableToolNames
        log.info { "Tools enabled (${toolNames.size}): [${toolNames.joinToString(", ")}]" }

        val botJob = botRunner.start()
        val schedulerJob = scheduler.launchIn(this)

        try {
            botJob.join()
        } finally {
            schedulerJob.cancelAndJoin()
        }
    } finally {
        executor?.close()
        http?.close()
        Db.disconnect()
    }
}
