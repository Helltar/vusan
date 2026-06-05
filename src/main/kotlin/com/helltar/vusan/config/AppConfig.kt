package com.helltar.vusan.config

import io.github.cdimascio.dotenv.dotenv
import kotlin.io.path.Path
import kotlin.io.path.isReadable
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds

data class AppConfig(
    val allowedIds: Set<Long>,
    val databasePath: String,
    val elevenLabsApiKey: String?,
    val elevenLabsTts: ElevenLabsTtsConfig?,
    val giphyApiKey: String?,
    val llmProvider: LlmProviderConfig,
    val maxTasksPerUser: Int,
    val openAiStt: OpenAiSttConfig?,
    val sandboxTimeoutSeconds: Long,
    val sandboxUrl: String?,
    val systemPrompt: String?,
    val taskMaxLatenessMinutes: Long,
    val taskPollIntervalSeconds: Long,
    val tavilyApiKey: String?,
    val telegramBotToken: String,
    val ytDlpCookiesFile: String?,
    val ytDlpPath: String
) {
    companion object {
        private const val DEFAULT_LLM_MODEL = "gpt-5.4-nano"
        private const val DEFAULT_LLM_PROVIDER = "openai"
        private const val DEFAULT_LLM_REQUEST_TIMEOUT_SECONDS = 120L
        private const val DEFAULT_MAX_TASKS_PER_USER = 5
        private const val DEFAULT_SANDBOX_TIMEOUT_SECONDS = 30L
        private const val DEFAULT_TASK_MAX_LATENESS_MINUTES = 60L
        private const val DEFAULT_TASK_POLL_INTERVAL_SECONDS = 30L

        private val dotenv = dotenv { ignoreIfMissing = true }
        private val elevenLabsKey = readEnv("ELEVENLABS_API_KEY")

        fun fromEnv(): AppConfig {
            return AppConfig(
                allowedIds = parseIdSet(readEnv("ALLOWED_IDS")),
                databasePath = readEnv("DB_FILE") ?: "data/db/vusan.db",
                elevenLabsApiKey = elevenLabsKey,
                giphyApiKey = readEnv("GIPHY_API_KEY"),
                llmProvider = resolveLlmProvider(),
                maxTasksPerUser = readEnv("MAX_TASKS_PER_USER")?.toIntOrNull() ?: DEFAULT_MAX_TASKS_PER_USER,
                openAiStt = resolveOpenAiStt(),
                sandboxTimeoutSeconds = readEnv("SANDBOX_TIMEOUT_SECONDS")?.toLongOrNull() ?: DEFAULT_SANDBOX_TIMEOUT_SECONDS,
                sandboxUrl = readEnv("SANDBOX_URL"),
                systemPrompt = resolveSystemPrompt(),
                taskMaxLatenessMinutes = readEnv("TASK_MAX_LATENESS_MINUTES")?.toLongOrNull() ?: DEFAULT_TASK_MAX_LATENESS_MINUTES,
                taskPollIntervalSeconds = readEnv("TASK_POLL_INTERVAL_SECONDS")?.toLongOrNull() ?: DEFAULT_TASK_POLL_INTERVAL_SECONDS,
                tavilyApiKey = readEnv("TAVILY_API_KEY"),
                telegramBotToken = requireEnv("TELEGRAM_BOT_TOKEN"),
                ytDlpCookiesFile = readEnv("YT_DLP_COOKIES_FILE"),
                ytDlpPath = readEnv("YT_DLP_PATH") ?: "yt-dlp",

                elevenLabsTts =
                    elevenLabsKey?.let {
                        ElevenLabsTtsConfig(
                            model = readEnv("ELEVENLABS_TTS_MODEL") ?: ElevenLabsTtsConfig.DEFAULT_MODEL,
                            voiceId = readEnv("ELEVENLABS_VOICE_ID") ?: ElevenLabsTtsConfig.DEFAULT_VOICE_ID,
                            outputFormat = readEnv("ELEVENLABS_TTS_OUTPUT_FORMAT") ?: ElevenLabsTtsConfig.DEFAULT_OUTPUT_FORMAT
                        )
                    }
            )
        }

        private fun resolveSystemPrompt(): String? {
            readEnv("SYSTEM_PROMPT")?.let { return it }
            val path = readEnv("SYSTEM_PROMPT_FILE") ?: return null
            val file = Path(path)
            require(file.isReadable()) { "SYSTEM_PROMPT_FILE=[$path] does not exist or is not readable" }
            return file.readText().trim().ifBlank { null }
        }

        private fun resolveOpenAiStt(): OpenAiSttConfig? {
            val key = readEnv("OPENAI_STT_API_KEY") ?: return null

            return OpenAiSttConfig(
                apiKey = key,
                model = readEnv("OPENAI_STT_MODEL") ?: OpenAiSttConfig.DEFAULT_MODEL,
                maxDurationSeconds = readEnv("OPENAI_STT_MAX_DURATION_SECONDS")?.toLongOrNull() ?: OpenAiSttConfig.DEFAULT_MAX_DURATION_SECONDS
            )
        }

        private fun resolveLlmProvider(): LlmProviderConfig {
            val raw = readEnv("LLM_PROVIDER") ?: DEFAULT_LLM_PROVIDER
            val requestTimeout = (readEnv("LLM_REQUEST_TIMEOUT_SECONDS")?.toLongOrNull() ?: DEFAULT_LLM_REQUEST_TIMEOUT_SECONDS).seconds

            return when (val provider = raw.trim().lowercase()) {
                "openai" ->
                    LlmProviderConfig.OpenAi(
                        apiKey = requireEnv("LLM_API_KEY"),
                        model = readEnv("LLM_MODEL") ?: DEFAULT_LLM_MODEL,
                        requestTimeout = requestTimeout
                    )

                "openai-compatible" ->
                    LlmProviderConfig.OpenAiCompatible(
                        baseUrl = requireEnv("LLM_BASE_URL"),
                        apiKey = requireEnv("LLM_API_KEY"),
                        model = requireEnv("LLM_MODEL"),
                        requestTimeout = requestTimeout
                    )

                else -> error("Unsupported LLM_PROVIDER=[$provider]. Supported values: openai, openai-compatible")
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
