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
    val maxMemoryPerScope: Int,
    val maxTasksPerUser: Int,
    val openAiStt: OpenAiSttConfig?,
    val sandboxTimeoutSeconds: Long,
    val sandboxUrl: String?,
    val systemPrompt: String?,
    val taskMaxLatenessMinutes: Long,
    val tavilyApiKey: String?,
    val telegramBotToken: String,
    val ytDlpCookiesFile: String?
) {
    companion object {
        private const val DEFAULT_LLM_REQUEST_TIMEOUT_SECONDS = 120L
        private const val DEFAULT_MAX_MEMORY_PER_SCOPE = 10
        private const val DEFAULT_MAX_TASKS_PER_USER = 5
        private const val DEFAULT_SANDBOX_TIMEOUT_SECONDS = 120L
        private const val DEFAULT_TASK_MAX_LATENESS_MINUTES = 60L

        private val dotenv = dotenv { ignoreIfMissing = true }

        fun fromEnv(): AppConfig {
            val elevenLabsKey = readEnv("ELEVENLABS_API_KEY")

            return AppConfig(
                allowedIds = parseIdSet(readEnv("ALLOWED_IDS")),
                databasePath = readEnv("DB_FILE") ?: "data/db/vusan.db",
                elevenLabsApiKey = elevenLabsKey,
                giphyApiKey = readEnv("GIPHY_API_KEY"),
                llmProvider = resolveLlmProvider(),
                openAiStt = resolveOpenAiStt(),
                sandboxUrl = readEnv("SANDBOX_URL"),
                systemPrompt = resolveSystemPrompt(),
                tavilyApiKey = readEnv("TAVILY_API_KEY"),
                telegramBotToken = requireEnv("TELEGRAM_BOT_TOKEN"),
                ytDlpCookiesFile = readEnv("YT_DLP_COOKIES_FILE"),

                maxMemoryPerScope = readEnv("MAX_MEMORY_PER_SCOPE")?.toIntOrNull() ?: DEFAULT_MAX_MEMORY_PER_SCOPE,
                maxTasksPerUser = readEnv("MAX_TASKS_PER_USER")?.toIntOrNull() ?: DEFAULT_MAX_TASKS_PER_USER,

                sandboxTimeoutSeconds =
                    readEnv("SANDBOX_TIMEOUT_SECONDS")?.toLongOrNull()
                        ?: DEFAULT_SANDBOX_TIMEOUT_SECONDS,

                taskMaxLatenessMinutes =
                    readEnv("TASK_MAX_LATENESS_MINUTES")?.toLongOrNull()
                        ?: DEFAULT_TASK_MAX_LATENESS_MINUTES,

                elevenLabsTts =
                    elevenLabsKey?.let {
                        ElevenLabsTtsConfig(
                            model = readEnv("ELEVENLABS_TTS_MODEL") ?: ElevenLabsTtsConfig.DEFAULT_MODEL,
                            voiceId = readEnv("ELEVENLABS_VOICE_ID") ?: ElevenLabsTtsConfig.DEFAULT_VOICE_ID
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
                maxDurationSeconds =
                    readEnv("OPENAI_STT_MAX_DURATION_SECONDS")?.toLongOrNull()
                        ?: OpenAiSttConfig.DEFAULT_MAX_DURATION_SECONDS
            )
        }

        private fun resolveLlmProvider(): LlmProviderConfig {
            val raw = requireEnv("LLM_PROVIDER")

            val requestTimeout =
                (readEnv("LLM_REQUEST_TIMEOUT_SECONDS")?.toLongOrNull()
                    ?: DEFAULT_LLM_REQUEST_TIMEOUT_SECONDS).seconds

            val provider = raw.trim().lowercase()

            if (provider == "openai-compatible") {
                return LlmProviderConfig.OpenAiCompatible(
                    baseUrl = requireEnv("LLM_BASE_URL"),
                    apiKey = requireEnv("LLM_API_KEY"),
                    model = requireEnv("LLM_MODEL"),
                    requestTimeout = requestTimeout
                )
            }

            val hosted =
                runCatching { HostedLlmProvider.valueOf(provider.uppercase()) }.getOrNull()
                    ?: error(
                        "Unsupported LLM_PROVIDER=[$provider]. " +
                                "Supported values: openai, anthropic, google, deepseek, openai-compatible"
                    )

            return LlmProviderConfig.Hosted(
                provider = hosted,
                apiKey = requireEnv("LLM_API_KEY"),
                model = requireEnv("LLM_MODEL"),
                requestTimeout = requestTimeout
            )
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
