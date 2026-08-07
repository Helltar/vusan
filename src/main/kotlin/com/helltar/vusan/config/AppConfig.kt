package com.helltar.vusan.config

import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import io.github.cdimascio.dotenv.dotenv
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.ZoneId
import kotlin.io.path.Path
import kotlin.io.path.isReadable
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds

data class AppConfig(
    val allowedIds: Set<Long>,
    val chatHistory: ConversationConfig = ConversationConfig(),
    val groupLog: GroupLogConfig = GroupLogConfig(),
    val databasePath: String,
    val elevenLabsApiKey: String?,
    val elevenLabsTts: ElevenLabsTtsConfig?,
    val giphyApiKey: String?,
    val llmProvider: LlmProviderConfig,
    val maxFollowUpsPerUser: Int,
    val maxMemoryPerScope: Int,
    val maxTasksPerUser: Int,
    val openAiImageApiKey: String?,
    val openAiImage: OpenAiImageConfig?,
    val openAiStt: OpenAiSttConfig?,
    val openAiVision: OpenAiVisionConfig?,
    val personality: String?,
    val sandboxTimeoutSeconds: Long,
    val sandboxUrl: String?,
    val searxngUrl: String?,
    val taskMaxLatenessMinutes: Long,
    val tavilyApiKey: String?,
    val telegramBotToken: String,
    val tokenBudget: TokenBudgetConfig = TokenBudgetConfig(),
    val ytDlpCookiesFile: String?
) {
    companion object {
        private const val DEFAULT_LLM_REQUEST_TIMEOUT_SECONDS = 120L
        private const val DEFAULT_MAX_FOLLOW_UPS_PER_USER = 3
        private const val DEFAULT_MAX_MEMORY_PER_SCOPE = 10
        private const val DEFAULT_MAX_TASKS_PER_USER = 5
        private const val DEFAULT_SANDBOX_TIMEOUT_SECONDS = 120L
        private const val DEFAULT_TASK_MAX_LATENESS_MINUTES = 60L

        private val dotenv = dotenv { ignoreIfMissing = true }

        private val log = KotlinLogging.logger {}

        fun fromEnv(): AppConfig {
            val elevenLabsKey = readEnv("ELEVENLABS_API_KEY")
            val openAiImageKey = readEnv("OPENAI_IMAGE_API_KEY")

            return AppConfig(
                allowedIds = parseIdSet(readEnv("ALLOWED_IDS")),
                chatHistory =
                    ConversationConfig(
                        maxRecentInteractions =
                            readEnv("CONVERSATION_MAX_RECENT_INTERACTIONS")?.toIntOrNull()
                                ?: ConversationConfig.DEFAULT_MAX_RECENT_INTERACTIONS,
                        maxStoredInteractions =
                            readEnv("CONVERSATION_MAX_STORED_INTERACTIONS")?.toIntOrNull()
                                ?: ConversationConfig.DEFAULT_MAX_STORED_INTERACTIONS,
                        retentionDays =
                            readEnv("CONVERSATION_RETENTION_DAYS")?.toIntOrNull()
                                ?: ConversationConfig.DEFAULT_RETENTION_DAYS
                    ),
                groupLog =
                    GroupLogConfig(
                        enabled = readEnv("GROUP_LOG_ENABLED")?.toBooleanStrictOrNull() ?: true,
                        retentionDays =
                            readEnv("GROUP_LOG_RETENTION_DAYS")?.toIntOrNull()
                                ?: GroupLogConfig.DEFAULT_RETENTION_DAYS,
                        maxMessagesPerChat =
                            readEnv("GROUP_LOG_MAX_MESSAGES_PER_CHAT")?.toIntOrNull()
                                ?: GroupLogConfig.DEFAULT_MAX_MESSAGES_PER_CHAT,
                        recentMessages =
                            readEnv("GROUP_LOG_RECENT_MESSAGES")?.toIntOrNull()
                                ?: GroupLogConfig.DEFAULT_RECENT_MESSAGES,
                        recentMinutes =
                            readEnv("GROUP_LOG_RECENT_MINUTES")?.toIntOrNull()
                                ?: GroupLogConfig.DEFAULT_RECENT_MINUTES
                    ),
                databasePath = readEnv("DB_FILE") ?: "data/db/vusan.db",
                elevenLabsApiKey = elevenLabsKey,
                giphyApiKey = readEnv("GIPHY_API_KEY"),
                llmProvider = resolveLlmProvider(),
                openAiImageApiKey = openAiImageKey,
                openAiStt = resolveOpenAiStt(),
                openAiVision = resolveOpenAiVision(),
                personality = resolvePersonality(),
                sandboxUrl = readEnv("SANDBOX_URL"),
                searxngUrl = readEnv("SEARXNG_URL"),
                tavilyApiKey = readEnv("TAVILY_API_KEY"),
                telegramBotToken = requireEnv("TELEGRAM_BOT_TOKEN"),
                tokenBudget = resolveTokenBudget(),
                ytDlpCookiesFile = readEnv("YT_DLP_COOKIES_FILE"),

                maxFollowUpsPerUser =
                    readEnv("MAX_FOLLOW_UPS_PER_USER")?.toIntOrNull() ?: DEFAULT_MAX_FOLLOW_UPS_PER_USER,

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
                    },

                openAiImage =
                    openAiImageKey?.let {
                        OpenAiImageConfig(
                            model = readEnv("OPENAI_IMAGE_MODEL") ?: OpenAiImageConfig.DEFAULT_MODEL,
                            quality = readEnv("OPENAI_IMAGE_QUALITY") ?: OpenAiImageConfig.DEFAULT_QUALITY
                        )
                    }
            )
        }

        private fun resolvePersonality(): String? {
            readEnv("PERSONALITY")?.let {
                log.info { "Personality: PERSONALITY env override (${it.length} chars)" }
                return it
            }

            val path =
                readEnv("PERSONALITY_FILE")
                    ?: run {
                        log.info { "Personality: built-in default (no PERSONALITY / PERSONALITY_FILE set)" }
                        return null
                    }

            val file = Path(path)

            require(file.isReadable()) { "PERSONALITY_FILE=[$path] does not exist or is not readable" }

            val text = file.readText().trim().ifBlank { null }

            if (text == null) {
                log.warn { "Personality: PERSONALITY_FILE=[$path] is blank — falling back to built-in default" }
            } else {
                log.info { "Personality: PERSONALITY_FILE=[$path] (${text.length} chars)" }
            }

            return text
        }

        // a mistyped budget must not read as "no budget": both values are rejected loudly instead of ignored.
        private fun resolveTokenBudget(): TokenBudgetConfig {
            val dailyTokens =
                readEnv("LLM_DAILY_TOKEN_BUDGET")?.let {
                    it.toLongOrNull() ?: error("LLM_DAILY_TOKEN_BUDGET=[$it] is not a number")
                }

            val zone =
                readEnv("LLM_TOKEN_BUDGET_TIMEZONE")?.let { raw ->
                    runCatching { ZoneId.of(raw) }
                        .getOrElse { error("Unsupported LLM_TOKEN_BUDGET_TIMEZONE=[$raw], expected a zone id like Europe/Kyiv") }
                } ?: TokenBudgetConfig.DEFAULT_ZONE

            val fairSharePercent =
                readEnv("LLM_TOKEN_BUDGET_FAIR_SHARE_AT_PERCENT")?.let {
                    it.toIntOrNull() ?: error("LLM_TOKEN_BUDGET_FAIR_SHARE_AT_PERCENT=[$it] is not a number")
                } ?: TokenBudgetConfig.DEFAULT_FAIR_SHARE_PERCENT

            return TokenBudgetConfig(dailyTokens = dailyTokens, zone = zone, fairSharePercent = fairSharePercent)
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

        private fun resolveOpenAiVision(): OpenAiVisionConfig? {
            val key = readEnv("OPENAI_VISION_API_KEY") ?: return null

            return OpenAiVisionConfig(
                apiKey = key,
                model = readEnv("OPENAI_VISION_MODEL") ?: OpenAiVisionConfig.DEFAULT_MODEL
            )
        }

        private fun resolveLlmProvider(): LlmProviderConfig {
            val raw = requireEnv("LLM_PROVIDER")

            val contextWindowTokens = readEnv("LLM_CONTEXT_WINDOW_TOKENS")?.toLongOrNull()

            val requestTimeout =
                (readEnv("LLM_REQUEST_TIMEOUT_SECONDS")?.toLongOrNull()
                    ?: DEFAULT_LLM_REQUEST_TIMEOUT_SECONDS).seconds

            val provider = raw.trim().lowercase()

            if (provider == "openai-compatible") {
                return LlmProviderConfig.OpenAiCompatible(
                    baseUrl = requireEnv("LLM_BASE_URL"),
                    apiKey = requireEnv("LLM_API_KEY"),
                    model = requireEnv("LLM_MODEL"),
                    endpoint = resolveOpenAiEndpoint(),
                    reasoningEffort = resolveReasoningEffort(),
                    requestTimeout = requestTimeout,
                    contextWindowTokens = contextWindowTokens
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
                requestTimeout = requestTimeout,
                contextWindowTokens = contextWindowTokens
            )
        }

        private fun resolveOpenAiEndpoint(): OpenAiEndpoint {
            val raw = readEnv("LLM_OPENAI_ENDPOINT") ?: return OpenAiEndpoint.COMPLETIONS

            return enumOrNull<OpenAiEndpoint>(raw)
                ?: error("Unsupported LLM_OPENAI_ENDPOINT=[$raw]. Supported values: ${supportedValues<OpenAiEndpoint>()}")
        }

        private fun resolveReasoningEffort(): ReasoningEffort? {
            val raw = readEnv("LLM_REASONING_EFFORT") ?: return null

            return enumOrNull<ReasoningEffort>(raw)
                ?: error("Unsupported LLM_REASONING_EFFORT=[$raw]. Supported values: ${supportedValues<ReasoningEffort>()}")
        }

        private inline fun <reified T : Enum<T>> enumOrNull(raw: String): T? =
            runCatching { enumValueOf<T>(raw.trim().uppercase()) }.getOrNull()

        private inline fun <reified T : Enum<T>> supportedValues(): String =
            enumValues<T>().joinToString { it.name.lowercase() }

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
