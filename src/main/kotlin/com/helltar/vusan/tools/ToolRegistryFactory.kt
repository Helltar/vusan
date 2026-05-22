package com.helltar.vusan.tools

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import com.helltar.vusan.agent.history.ChatHistoryRepository
import com.helltar.vusan.config.AppConfig
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.tools.currency.CurrencyTools
import com.helltar.vusan.tools.currency.ExchangeRateClient
import com.helltar.vusan.tools.files.FileTools
import com.helltar.vusan.tools.giphy.GiphyClient
import com.helltar.vusan.tools.giphy.GiphyTools
import com.helltar.vusan.tools.memory.MemoryTools
import com.helltar.vusan.tools.message.MessageTools
import com.helltar.vusan.tools.poll.PollTools
import com.helltar.vusan.tools.quiz.QuizTools
import com.helltar.vusan.tools.reaction.ReactionTools
import com.helltar.vusan.tools.tavily.TavilyClient
import com.helltar.vusan.tools.tavily.TavilyTools
import com.helltar.vusan.tools.tgchannel.KoogTelegramChannelImageDescriber
import com.helltar.vusan.tools.tgchannel.TelegramChannelClient
import com.helltar.vusan.tools.tgchannel.TelegramChannelTools
import com.helltar.vusan.tools.vision.KoogRepliedPhotoVisionClient
import com.helltar.vusan.tools.vision.VisionTools
import com.helltar.vusan.tools.voice.ElevenLabsTtsClient
import com.helltar.vusan.tools.voice.VoiceTools
import com.helltar.vusan.tools.youtube.YouTubeMusicTools
import com.helltar.vusan.tools.youtube.YtDlpClient
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*

class ToolRegistryFactory(
    http: HttpClient,
    config: AppConfig,
    private val history: ChatHistoryRepository,
    promptExecutor: PromptExecutor,
    model: LLModel
) {

    private companion object {
        val log = KotlinLogging.logger {}
    }

    private val currency = CurrencyTools(ExchangeRateClient(http))
    private val telegramChannelClient = TelegramChannelClient(http)
    private val telegramChannel = TelegramChannelTools(telegramChannelClient, KoogTelegramChannelImageDescriber(promptExecutor, model))
    private val ytDlpClient = YtDlpClient(config.ytDlpPath, config.ytDlpCookiesFile)
    private val repliedPhotoVisionClient = KoogRepliedPhotoVisionClient(promptExecutor, model)

    private val tavilyClient = optional("TAVILY_API_KEY", config.tavilyApiKey, "Tavily web search tool") { TavilyClient(http, it) }
    private val giphyClient = optional("GIPHY_API_KEY", config.giphyApiKey, "Giphy GIF tool") { GiphyClient(http, it) }
    private val elevenLabsTtsClient = optional("ELEVENLABS_API_KEY", config.elevenLabsApiKey, "voice/TTS tool") { ElevenLabsTtsClient(http, it) }
    private val elevenLabsTts = config.elevenLabsTts

    fun buildRegistry(outbox: BotOutbox): ToolRegistry =
        ToolRegistry {
            tools(MessageTools(outbox))
            tools(ReactionTools(outbox))
            tools(currency)
            tools(telegramChannel)
            tools(YouTubeMusicTools(ytDlpClient, outbox))
            tools(FileTools(outbox))
            tools(QuizTools(outbox))
            tools(PollTools(outbox))
            tools(MemoryTools(history, outbox))
            tools(VisionTools(repliedPhotoVisionClient, outbox.repliedPhoto))

            tavilyClient?.let { tools(TavilyTools(it, outbox)) }
            giphyClient?.let { tools(GiphyTools(it, outbox)) }

            if (elevenLabsTtsClient != null && elevenLabsTts != null) {
                tools(VoiceTools(elevenLabsTtsClient, elevenLabsTts, outbox))
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
