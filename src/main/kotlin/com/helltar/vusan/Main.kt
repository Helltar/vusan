package com.helltar.vusan

import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import com.helltar.vusan.agent.AgentFactory
import com.helltar.vusan.agent.AgentRunner
import com.helltar.vusan.agent.history.ChatHistoryRepository
import com.helltar.vusan.agent.memory.MemoryRepository
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
import io.github.oshai.kotlinlogging.KotlinLogging
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import io.ktor.client.*
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlin.time.Duration.Companion.minutes

private val log = KotlinLogging.logger {}

suspend fun main() = coroutineScope {
    val config = AppConfig.fromEnv()

    var http: HttpClient? = null
    var executor: MultiLLMPromptExecutor? = null

    try {
        Db.connect(config)

        http = Http.createClient()
        val llm = resolveLlmRuntime(config.llmProvider)
        executor = MultiLLMPromptExecutor(llm.model.provider to llm.client)

        val history = ChatHistoryRepository()
        val memory = MemoryRepository(config.maxMemoryPerScope)
        val tasks = TasksRepository()

        val toolRegistryFactory = ToolRegistryFactory(http, config, history, memory, tasks, executor, llm.model)
        val agentFactory = AgentFactory(executor, toolRegistryFactory, llm.model, llm.chatParams, config.systemPrompt)
        val agentRunner = AgentRunner(agentFactory, history, memory)

        val voiceTranscriber =
            config.openAiStt?.let { sttConfig ->
                VoiceTranscriber(OpenAiWhisperClient(http, sttConfig), sttConfig)
            } ?: run {
                log.warn { "OPENAI_STT_API_KEY not set — voice message transcription and video sound disabled" }
                null
            }

        val client = OkHttpTelegramClient(config.telegramBotToken)
        val delivery = TelegramDelivery(client)
        val botRunner =
            TelegramBotRunner(client, config.telegramBotToken, delivery, agentRunner, history, config.allowedIds, voiceTranscriber)

        val maxLateness = config.taskMaxLatenessMinutes.minutes
        val scheduler = TaskScheduler(tasks, agentRunner, delivery, history, maxLateness)

        log.info { "Starting Vusan: provider=[${llm.providerLabel}] model=[${llm.model.id}]" }
        val toolNames = toolRegistryFactory.availableToolNames
        log.info { "Tools enabled (${toolNames.size}): [${toolNames.joinToString(", ")}]" }

        val botJob = botRunner.start(this)
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
