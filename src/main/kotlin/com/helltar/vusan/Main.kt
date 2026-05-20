package com.helltar.vusan

import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMProvider
import com.helltar.vusan.agent.AgentRunner
import com.helltar.vusan.agent.AgentFactory
import com.helltar.vusan.agent.history.ChatHistoryRepository
import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.config.OpenAiModelResolver
import com.helltar.vusan.infra.Db
import com.helltar.vusan.infra.Http
import com.helltar.vusan.telegram.TelegramBotRunner
import com.helltar.vusan.tools.ToolRegistryFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking

private val log = KotlinLogging.logger {}

fun main() = runBlocking {
    val config = AppConfig.fromEnv()
    val model = OpenAiModelResolver.resolve(config.openAiModel)

    Db.connect(config)

    val http = Http.createClient()

    http.use { http ->
        val llmClients = LLMProvider.OpenAI to OpenAILLMClient(config.openAiApiKey)

        MultiLLMPromptExecutor(llmClients).use { executor ->
            val history = ChatHistoryRepository()

            val toolRegistryFactory =
                ToolRegistryFactory(
                    http = http,
                    config = config,
                    history = history,
                    promptExecutor = executor,
                    model = model
                )

            val agentFactory =
                AgentFactory(
                    promptExecutor = executor,
                    toolRegistryFactory = toolRegistryFactory,
                    model = model
                )

            val agentRunner =
                AgentRunner(
                    agentFactory = agentFactory,
                    history = history
                )

            val bot =
                TelegramBotRunner(
                    botToken = config.telegramBotToken,
                    agent = agentRunner,
                    history = history,
                    allowedIds = config.allowedIds
                )

            log.info { "Starting Vusan, model=${model.id}" }

            bot.start().join()
        }
    }
}
