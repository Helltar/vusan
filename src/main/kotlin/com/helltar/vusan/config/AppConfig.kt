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
    val agentMaxIterations: Int,
    val allowedIds: Set<Long>,
    val appearance: String?,
    val bannedIds: Set<Long> = emptySet(),
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
    val selfImageFile: String?,
    val taskMaxLatenessMinutes: Long,
    val tavilyApiKey: String?,
    val telegramBotToken: String,
    val tokenBudget: TokenBudgetConfig = TokenBudgetConfig(),
    val ytDlpCookiesFile: String?
) {
    init {
        require(agentMaxIterations > 0) { "AGENT_MAX_ITERATIONS must be positive" }
        require(maxFollowUpsPerUser >= 0) { "MAX_FOLLOW_UPS_PER_USER must not be negative" }
        require(maxMemoryPerScope >= 0) { "MAX_MEMORY_PER_SCOPE must not be negative" }
        require(maxTasksPerUser >= 0) { "MAX_TASKS_PER_USER must not be negative" }
        require(sandboxTimeoutSeconds > 0) { "SANDBOX_TIMEOUT_SECONDS must be positive" }
        require(taskMaxLatenessMinutes >= 0) { "TASK_MAX_LATENESS_MINUTES must not be negative" }
    }

    companion object {
        private const val DEFAULT_AGENT_MAX_ITERATIONS = 70
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
            val llmProvider = resolveLlmProvider()

            val imageRoute = resolveImageRoute(openAiImageKey != null, llmProvider)

            return AppConfig(
                agentMaxIterations = readIntEnv("AGENT_MAX_ITERATIONS") ?: DEFAULT_AGENT_MAX_ITERATIONS,

                allowedIds = readIdSetEnv("ALLOWED_IDS"),
                appearance = resolveAppearance(),
                bannedIds = readIdSetEnv("BANNED_IDS"),
                chatHistory =
                    ConversationConfig(
                        maxRecentInteractions =
                            readIntEnv("CONVERSATION_MAX_RECENT_INTERACTIONS")
                                ?: ConversationConfig.DEFAULT_MAX_RECENT_INTERACTIONS,
                        maxStoredInteractions =
                            readIntEnv("CONVERSATION_MAX_STORED_INTERACTIONS")
                                ?: ConversationConfig.DEFAULT_MAX_STORED_INTERACTIONS,
                        retentionDays =
                            readIntEnv("CONVERSATION_RETENTION_DAYS")
                                ?: ConversationConfig.DEFAULT_RETENTION_DAYS
                    ),
                groupLog =
                    GroupLogConfig(
                        enabled = readBooleanEnv("GROUP_LOG_ENABLED") ?: true,
                        retentionDays =
                            readIntEnv("GROUP_LOG_RETENTION_DAYS")
                                ?: GroupLogConfig.DEFAULT_RETENTION_DAYS,
                        maxMessagesPerChat =
                            readIntEnv("GROUP_LOG_MAX_MESSAGES_PER_CHAT")
                                ?: GroupLogConfig.DEFAULT_MAX_MESSAGES_PER_CHAT,
                        recentMessages =
                            readIntEnv("GROUP_LOG_RECENT_MESSAGES")
                                ?: GroupLogConfig.DEFAULT_RECENT_MESSAGES,
                        recentMinutes =
                            readIntEnv("GROUP_LOG_RECENT_MINUTES")
                                ?: GroupLogConfig.DEFAULT_RECENT_MINUTES
                    ),
                databasePath = readEnv("DB_FILE") ?: "data/db/vusan.db",
                elevenLabsApiKey = elevenLabsKey,
                giphyApiKey = readEnv("GIPHY_API_KEY"),
                llmProvider = llmProvider,
                openAiImageApiKey = openAiImageKey,
                openAiStt = resolveOpenAiStt(),
                openAiVision = resolveOpenAiVision(),
                personality = resolvePersonality(),
                sandboxUrl = readEnv("SANDBOX_URL"),
                searxngUrl = readEnv("SEARXNG_URL"),
                selfImageFile = readEnv("SELF_IMAGE_FILE"),
                tavilyApiKey = readEnv("TAVILY_API_KEY"),
                telegramBotToken = requireEnv("TELEGRAM_BOT_TOKEN"),
                tokenBudget = resolveTokenBudget(),
                ytDlpCookiesFile = readEnv("YT_DLP_COOKIES_FILE"),

                maxFollowUpsPerUser = readIntEnv("MAX_FOLLOW_UPS_PER_USER") ?: DEFAULT_MAX_FOLLOW_UPS_PER_USER,
                maxMemoryPerScope = readIntEnv("MAX_MEMORY_PER_SCOPE") ?: DEFAULT_MAX_MEMORY_PER_SCOPE,
                maxTasksPerUser = readIntEnv("MAX_TASKS_PER_USER") ?: DEFAULT_MAX_TASKS_PER_USER,

                sandboxTimeoutSeconds = readLongEnv("SANDBOX_TIMEOUT_SECONDS") ?: DEFAULT_SANDBOX_TIMEOUT_SECONDS,

                taskMaxLatenessMinutes =
                    readLongEnv("TASK_MAX_LATENESS_MINUTES") ?: DEFAULT_TASK_MAX_LATENESS_MINUTES,

                elevenLabsTts =
                    elevenLabsKey?.let {
                        ElevenLabsTtsConfig(
                            model = readEnv("ELEVENLABS_TTS_MODEL") ?: ElevenLabsTtsConfig.DEFAULT_MODEL,
                            voiceId = readEnv("ELEVENLABS_VOICE_ID") ?: ElevenLabsTtsConfig.DEFAULT_VOICE_ID
                        )
                    },

                openAiImage =
                    imageRoute?.let { route ->
                        OpenAiImageConfig(
                            model = readEnv("OPENAI_IMAGE_MODEL") ?: defaultImageModel(route),
                            quality = readEnv("OPENAI_IMAGE_QUALITY") ?: OpenAiImageConfig.DEFAULT_QUALITY,
                            route = route
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

        // what the bot looks like, kept out of the personality block on purpose: it is written for the
        // image model, and a chat model handed a physical description tends to recite it.
        private fun resolveAppearance(): String? {
            val text =
                readEnv("APPEARANCE")
                    ?: readEnv("APPEARANCE_FILE")?.let { path ->
                        val file = Path(path)

                        require(file.isReadable()) { "APPEARANCE_FILE=[$path] does not exist or is not readable" }

                        file.readText()
                    }

            return text?.trim()?.ifBlank { null }?.also { log.info { "Appearance: ${it.length} chars" } }
        }

        private fun resolveTokenBudget(): TokenBudgetConfig {
            val zone =
                readEnv("LLM_TOKEN_BUDGET_TIMEZONE")?.let { raw ->
                    runCatching { ZoneId.of(raw) }
                        .getOrElse { error("Unsupported LLM_TOKEN_BUDGET_TIMEZONE=[$raw], expected a zone id like Europe/Kyiv") }
                } ?: TokenBudgetConfig.DEFAULT_ZONE

            return TokenBudgetConfig(
                dailyTokens = readLongEnv("LLM_DAILY_TOKEN_BUDGET"),
                zone = zone,
                fairSharePercent =
                    readIntEnv("LLM_TOKEN_BUDGET_FAIR_SHARE_AT_PERCENT")
                        ?: TokenBudgetConfig.DEFAULT_FAIR_SHARE_PERCENT
            )
        }

        private fun resolveOpenAiStt(): OpenAiSttConfig? {
            val key = readEnv("OPENAI_STT_API_KEY") ?: return null

            return OpenAiSttConfig(
                apiKey = key,
                model = readEnv("OPENAI_STT_MODEL") ?: OpenAiSttConfig.DEFAULT_MODEL,
                maxDurationSeconds =
                    readLongEnv("OPENAI_STT_MAX_DURATION_SECONDS") ?: OpenAiSttConfig.DEFAULT_MAX_DURATION_SECONDS
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

            val contextWindowTokens = readLongEnv("LLM_CONTEXT_WINDOW_TOKENS")

            val requestTimeout =
                (readLongEnv("LLM_REQUEST_TIMEOUT_SECONDS") ?: DEFAULT_LLM_REQUEST_TIMEOUT_SECONDS).seconds

            val provider = raw.trim().lowercase()

            if (provider == "codex") {
                return LlmProviderConfig.Codex(
                    model = requireEnv("LLM_MODEL"),
                    reasoningEffort = resolveReasoningEffort(),
                    authFile = defaultCodexAuthFile(readEnv("CODEX_HOME")),
                    requestTimeout = requestTimeout,
                    contextWindowTokens = contextWindowTokens
                )
            }

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
                                "Supported values: openai, anthropic, google, deepseek, openai-compatible, codex"
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

        private fun readIntEnv(env: String): Int? = parseIntEnv(env, readEnv(env))

        private fun readLongEnv(env: String): Long? = parseLongEnv(env, readEnv(env))

        private fun readBooleanEnv(env: String): Boolean? = parseBooleanEnv(env, readEnv(env))

        private fun readIdSetEnv(env: String): Set<Long> = parseIdSetEnv(env, readEnv(env))
    }
}

// a value that is set but unreadable must not fall back to the default: writing the variable down at all
// says the default was not wanted, so a typo stops the startup instead of silently restoring it.
internal fun parseIntEnv(env: String, raw: String?): Int? =
    raw?.let { it.trim().toIntOrNull() ?: error("$env=[$it] is not a whole number") }

internal fun parseLongEnv(env: String, raw: String?): Long? =
    raw?.let { it.trim().toLongOrNull() ?: error("$env=[$it] is not a whole number") }

// case-insensitive on purpose: `toBooleanStrictOrNull` on its own rejects `False` and `TRUE`, and reading
// those as "unset" would leave the feature running in whichever state the default happens to be.
internal fun parseBooleanEnv(env: String, raw: String?): Boolean? =
    raw?.let {
        it.trim().lowercase().toBooleanStrictOrNull()
            ?: error("$env=[$it] is not a boolean, expected true or false")
    }

// a dropped id fails open on BANNED_IDS — the person stays unbanned — so an unreadable one is an error
// rather than something to skip.
internal fun parseIdSetEnv(env: String, raw: String?): Set<Long> =
    raw
        ?.split(',', ' ', '\n', '\t', ';')
        ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        ?.map { it.toLongOrNull() ?: error("$env contains [$it], which is not a numeric telegram id") }
        ?.toSet()
        .orEmpty()
