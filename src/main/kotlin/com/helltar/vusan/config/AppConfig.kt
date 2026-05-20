package com.helltar.vusan.config

import io.github.cdimascio.dotenv.dotenv

data class AppConfig(
    val telegramBotToken: String,
    val llmProvider: LlmProviderConfig,
    val elevenLabsApiKey: String?,
    val tavilyApiKey: String?,
    val giphyApiKey: String?,
    val ytDlpPath: String,
    val ytDlpCookiesFile: String?,
    val elevenLabsTts: ElevenLabsTtsConfig?,
    val databasePath: String,
    val allowedIds: Set<Long>
) {
    companion object {
        private const val DEFAULT_LLM_PROVIDER = "openai"
        private const val DEFAULT_OPENAI_MODEL = "gpt-5.4-nano"
        private const val DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434"

        private val dotenv = dotenv { ignoreIfMissing = true }
        private val elevenLabsKey = readEnv("ELEVENLABS_API_KEY")

        fun fromEnv(): AppConfig {
            return AppConfig(
                telegramBotToken = requireEnv("TELEGRAM_BOT_TOKEN"),
                llmProvider = resolveLlmProvider(),
                allowedIds = parseIdSet(readEnv("ALLOWED_IDS")),
                databasePath = readEnv("DB_FILE") ?: "data/db/vusan.db",
                tavilyApiKey = readEnv("TAVILY_API_KEY"),
                giphyApiKey = readEnv("GIPHY_API_KEY"),
                elevenLabsApiKey = elevenLabsKey,
                elevenLabsTts =
                    elevenLabsKey?.let {
                        ElevenLabsTtsConfig(
                            model = readEnv("ELEVENLABS_TTS_MODEL") ?: ElevenLabsTtsConfig.DEFAULT_MODEL,
                            voiceId = readEnv("ELEVENLABS_VOICE_ID") ?: ElevenLabsTtsConfig.DEFAULT_VOICE_ID,
                            outputFormat = readEnv("ELEVENLABS_TTS_OUTPUT_FORMAT") ?: ElevenLabsTtsConfig.DEFAULT_OUTPUT_FORMAT
                        )
                    },
                ytDlpPath = readEnv("YT_DLP_PATH") ?: "yt-dlp",
                ytDlpCookiesFile = readEnv("YT_DLP_COOKIES_FILE")
            )
        }

        private fun resolveLlmProvider(): LlmProviderConfig {
            val raw = readEnv("LLM_PROVIDER") ?: DEFAULT_LLM_PROVIDER

            return when (val provider = raw.trim().lowercase()) {
                "openai" ->
                    LlmProviderConfig.OpenAi(
                        apiKey = requireEnv("OPENAI_API_KEY"),
                        model = readEnv("OPENAI_MODEL") ?: DEFAULT_OPENAI_MODEL
                    )

                "ollama" ->
                    LlmProviderConfig.Ollama(
                        baseUrl = readEnv("OLLAMA_BASE_URL") ?: DEFAULT_OLLAMA_BASE_URL,
                        model = requireEnv("OLLAMA_MODEL")
                    )

                else -> error("Unsupported LLM_PROVIDER=[$provider]. Supported values: openai, ollama")
            }
        }

        private fun readEnv(env: String): String? =
            dotenv[env]?.takeIf { it.isNotBlank() }

        private fun requireEnv(env: String): String =
            requireNotNull(readEnv(env)) { "Missing required environment variable $env" }

        private fun parseIdSet(raw: String?): Set<Long> =
            raw
                ?.split(',', ' ', '\n', '\t', ';')
                ?.mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toLongOrNull() }
                ?.toSet()
                .orEmpty()
    }
}
