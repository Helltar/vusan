package com.helltar.vusan.config

import ai.koog.prompt.executor.clients.openai.base.models.ServiceTier
import com.helltar.vusan.request.AccessPolicy
import com.helltar.vusan.request.Platform
import com.helltar.vusan.request.UserRef
import io.github.cdimascio.dotenv.dotenv
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.io.path.Path
import kotlin.io.path.isReadable
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class AppConfig(
    val accessPolicy: AccessPolicy,
    val agentMaxIterations: Int,
    val appearance: String?,
    val chatHistory: ConversationConfig = ConversationConfig(),
    val databasePath: String,
    val elevenLabsApiKey: String?,
    val elevenLabsTts: ElevenLabsTtsConfig?,
    val giphyApiKey: String?,
    val groupLog: GroupLogConfig = GroupLogConfig(),
    val llmProvider: LlmProviderConfig,
    val llmFallback: LlmProviderConfig? = null,
    val maxConcurrentTurns: Int,
    val openAiImage: OpenAiImageConfig?,
    val openAiImageApiKey: String?,
    val openAiStt: OpenAiSttConfig?,
    val openAiVision: OpenAiVisionConfig?,
    val personality: String?,
    val regolithToken: String?,
    val regolithUrl: String?,
    val searxngUrl: String?,
    val selfImageFile: String?,
    val stickersEnabled: Boolean = true,
    val tavilyApiKey: String?,
    val telegramBotToken: String,
    val ytDlpCookiesFile: String?,
) {

    init {
        require(agentMaxIterations > 0) { "AGENT_MAX_ITERATIONS must be positive" }
        require(maxConcurrentTurns > 0) { "MAX_CONCURRENT_TURNS must be positive" }
        require(regolithUrl == null || !regolithToken.isNullOrBlank()) { "Sandbox API authentication is required" }
    }

    companion object {
        private const val DEFAULT_AGENT_MAX_ITERATIONS = 200
        private const val DEFAULT_LLM_REQUEST_TIMEOUT_SECONDS = 120L
        private const val LLM_PREFIX = "LLM"
        private const val LLM_FALLBACK_PREFIX = "LLM_FALLBACK"
        private const val DEFAULT_MAX_CONCURRENT_TURNS = 8

        private val dotenv = dotenv { ignoreIfMissing = true }

        private val log = KotlinLogging.logger {}

        fun fromEnv(): AppConfig {
            val elevenLabsKey = readEnv("ELEVENLABS_API_KEY")
            val openAiImageKey = readEnv("OPENAI_IMAGE_API_KEY")
            val llmProvider = resolveLlmProvider()
            val llmFallback = resolveLlmFallback(llmProvider)
            val imageRoute = resolveImageRoute(openAiImageKey != null, llmProvider)
            val regolithUrl = readEnv("REGOLITH_URL")

            return AppConfig(
                accessPolicy = AccessPolicy(allowed = readIdSetEnv("ALLOWED_IDS"), banned = readIdSetEnv("BANNED_IDS")),
                agentMaxIterations = readIntEnv("AGENT_MAX_ITERATIONS") ?: DEFAULT_AGENT_MAX_ITERATIONS,
                appearance = resolveAppearance(),
                databasePath = readEnv("DB_FILE") ?: "data/db/vusan.db",
                elevenLabsApiKey = elevenLabsKey,
                giphyApiKey = readEnv("GIPHY_API_KEY"),
                llmProvider = llmProvider,
                llmFallback = llmFallback,
                maxConcurrentTurns = readIntEnv("MAX_CONCURRENT_TURNS") ?: DEFAULT_MAX_CONCURRENT_TURNS,
                openAiImageApiKey = openAiImageKey,
                openAiStt = resolveOpenAiStt(),
                openAiVision = resolveOpenAiVision(),
                personality = resolvePersonality(),
                regolithToken = regolithUrl?.let { readServiceToken("REGOLITH", readEnv("REGOLITH_TOKEN")) },
                regolithUrl = regolithUrl,
                searxngUrl = readEnv("SEARXNG_URL"),
                selfImageFile = readEnv("SELF_IMAGE_FILE"),
                stickersEnabled = readBooleanEnv("STICKERS_ENABLED") ?: true,
                tavilyApiKey = readEnv("TAVILY_API_KEY"),
                telegramBotToken = requireEnv("TELEGRAM_BOT_TOKEN"),
                ytDlpCookiesFile = readEnv("YT_DLP_COOKIES_FILE"),

                chatHistory =
                    ConversationConfig(
                        retentionDays =
                            readIntEnv("CONVERSATION_RETENTION_DAYS")
                                ?: ConversationConfig.DEFAULT_RETENTION_DAYS,
                    ),

                groupLog =
                    GroupLogConfig(
                        enabled = readBooleanEnv("GROUP_LOG_ENABLED") ?: true,

                        retentionDays =
                            readIntEnv("GROUP_LOG_RETENTION_DAYS")
                                ?: GroupLogConfig.DEFAULT_RETENTION_DAYS,
                    ),

                elevenLabsTts =
                    elevenLabsKey?.let {
                        ElevenLabsTtsConfig(
                            model = readEnv("ELEVENLABS_TTS_MODEL") ?: ElevenLabsTtsConfig.DEFAULT_MODEL,
                            voiceId = readEnv("ELEVENLABS_VOICE_ID") ?: ElevenLabsTtsConfig.DEFAULT_VOICE_ID,
                        )
                    },

                openAiImage =
                    imageRoute?.let { route ->
                        OpenAiImageConfig(
                            model = readEnv("OPENAI_IMAGE_MODEL") ?: defaultImageModel(route),
                            quality = readEnv("OPENAI_IMAGE_QUALITY") ?: OpenAiImageConfig.DEFAULT_QUALITY,
                            moderation = readEnv("OPENAI_IMAGE_MODERATION") ?: OpenAiImageConfig.DEFAULT_MODERATION,
                            route = route,
                        )
                    },
            )
        }

        private fun resolvePersonality(): String? {
            val path =
                readEnv("PERSONALITY_FILE")
                    ?: run {
                        log.info { "Personality: built-in default (no PERSONALITY_FILE set)" }
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
                readEnv("APPEARANCE_FILE")?.let { path ->
                    val file = Path(path)

                    require(file.isReadable()) { "APPEARANCE_FILE=[$path] does not exist or is not readable" }

                    file.readText()
                }

            return text?.trim()?.ifBlank { null }?.also { log.info { "Appearance: ${it.length} chars" } }
        }

        private fun resolveOpenAiStt(): OpenAiSttConfig? {
            val key = readEnv("OPENAI_STT_API_KEY") ?: return null

            return OpenAiSttConfig(
                apiKey = key,
                model = readEnv("OPENAI_STT_MODEL") ?: OpenAiSttConfig.DEFAULT_MODEL,
                maxDurationSeconds =
                    readLongEnv("OPENAI_STT_MAX_DURATION_SECONDS") ?: OpenAiSttConfig.DEFAULT_MAX_DURATION_SECONDS,
            )
        }

        private fun resolveOpenAiVision(): OpenAiVisionConfig? {
            val key = readEnv("OPENAI_VISION_API_KEY") ?: return null

            return OpenAiVisionConfig(
                apiKey = key,
                model = readEnv("OPENAI_VISION_MODEL") ?: OpenAiVisionConfig.DEFAULT_MODEL,
            )
        }

        private fun resolveLlmProvider(): LlmProviderConfig = resolveLlmProvider(LLM_PREFIX, fallbackTimeout = null)

        // the same settings again under LLM_FALLBACK_, a second provider that answers while the first is
        // out. a subscription may stand on either side, but not both: the CODEX_* settings and the signed-in
        // account are one set, and two subscriptions would need two.
        private fun resolveLlmFallback(primary: LlmProviderConfig): LlmProviderConfig? {
            readEnv("${LLM_FALLBACK_PREFIX}_PROVIDER") ?: return null

            val fallback = resolveLlmProvider(LLM_FALLBACK_PREFIX, fallbackTimeout = primary.requestTimeout)

            require(primary !is LlmProviderConfig.Codex || fallback !is LlmProviderConfig.Codex) {
                "${LLM_FALLBACK_PREFIX}_PROVIDER cannot be codex when LLM_PROVIDER is: there is one signed-in account"
            }

            return fallback
        }

        private fun resolveLlmProvider(prefix: String, fallbackTimeout: Duration?): LlmProviderConfig {
            val raw = requireEnv("${prefix}_PROVIDER")

            val contextWindowTokens = readLongEnv("${prefix}_CONTEXT_WINDOW_TOKENS")

            val requestTimeout =
                readLongEnv("${prefix}_REQUEST_TIMEOUT_SECONDS")?.seconds
                    ?: fallbackTimeout
                    ?: DEFAULT_LLM_REQUEST_TIMEOUT_SECONDS.seconds

            val provider = raw.trim().lowercase()

            if (provider == "codex") {
                return LlmProviderConfig.Codex(
                    model = requireEnv("${prefix}_MODEL"),
                    reasoningEffort = resolveReasoningEffort(prefix),
                    serviceTier = resolveCodexServiceTier(),
                    imageGeneration = readBooleanEnv("CODEX_IMAGE_GENERATION_ENABLED") ?: true,
                    clientVersion = resolveCodexClientVersion(),
                    authFile = defaultCodexAuthFile(readEnv("CODEX_HOME")),
                    requestTimeout = requestTimeout,
                    contextWindowTokens = contextWindowTokens,
                )
            }

            if (provider == "openai-compatible") {
                return LlmProviderConfig.OpenAiCompatible(
                    baseUrl = requireEnv("${prefix}_BASE_URL"),
                    apiKey = requireEnv("${prefix}_API_KEY"),
                    model = requireEnv("${prefix}_MODEL"),
                    endpoint = resolveOpenAiEndpoint(prefix),
                    reasoningEffort = resolveReasoningEffort(prefix),
                    requestTimeout = requestTimeout,
                    contextWindowTokens = contextWindowTokens,
                )
            }

            val hosted =
                runCatching { HostedLlmProvider.valueOf(provider.uppercase()) }.getOrNull()
                    ?: error(
                        "Unsupported ${prefix}_PROVIDER=[$provider]. " +
                                "Supported values: openai, anthropic, google, deepseek, openai-compatible, codex",
                    )

            return LlmProviderConfig.Hosted(
                provider = hosted,
                apiKey = requireEnv("${prefix}_API_KEY"),
                model = requireEnv("${prefix}_MODEL"),
                reasoningEffort = resolveReasoningEffort(prefix),
                requestTimeout = requestTimeout,
                contextWindowTokens = contextWindowTokens,
            )
        }

        private fun resolveOpenAiEndpoint(prefix: String): OpenAiEndpoint {
            val raw = readEnv("${prefix}_OPENAI_ENDPOINT") ?: return OpenAiEndpoint.COMPLETIONS

            return enumOrNull<OpenAiEndpoint>(raw)
                ?: error("Unsupported ${prefix}_OPENAI_ENDPOINT=[$raw]. Supported values: ${supportedValues<OpenAiEndpoint>()}")
        }

        private fun resolveReasoningEffort(prefix: String): ReasoningEffort? {
            val raw = readEnv("${prefix}_REASONING_EFFORT") ?: return null

            return enumOrNull<ReasoningEffort>(raw)
                ?: error("Unsupported ${prefix}_REASONING_EFFORT=[$raw]. Supported values: ${supportedValues<ReasoningEffort>()}")
        }

        // `default` is Codex's own sentinel for "no tier chosen" rather than a tier the catalog offers, so
        // it reads as off here too instead of failing the catalog check with a value that means nothing.
        private fun resolveCodexServiceTier(): ServiceTier? {
            val raw = readEnv("CODEX_SERVICE_TIER") ?: return null

            val tier =
                enumOrNull<ServiceTier>(raw)
                    ?: error("Unsupported CODEX_SERVICE_TIER=[$raw]. Supported values: ${supportedValues<ServiceTier>()}")

            return tier.takeUnless { it == ServiceTier.DEFAULT }
        }

        // the catalog Codex answers with is filtered by this, so it is how an operator reaches a model
        // that needs a newer CLI than this build ships a floor for, without waiting for a release.
        private fun resolveCodexClientVersion(): String? {
            val raw = readEnv("CODEX_CLIENT_VERSION")?.trim() ?: return null

            return raw.takeIf { CODEX_VERSION.matches(it) }
                ?: error("Unsupported CODEX_CLIENT_VERSION=[$raw]. Expected a Codex CLI version such as 0.153.4")
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

        private fun readIdSetEnv(env: String): Set<String> = parseIdSetEnv(env, readEnv(env))
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

/**
 * Parses an allow/ban list into platform-qualified keys.
 *
 * A bare number is read as a Telegram id, which is what every existing deployment has written; a
 * `platform:id` entry names its own. A dropped id fails open on `BANNED_IDS` — the person stays
 * unbanned — so an entry that cannot be read is an error rather than something to skip.
 */
internal fun parseIdSetEnv(env: String, raw: String?): Set<String> =
    raw
        ?.split(',', ' ', '\n', '\t', ';')
        ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        ?.map { entry -> parsePolicyId(env, entry) }
        ?.toSet()
        .orEmpty()

private fun parsePolicyId(env: String, entry: String): String {
    val platform = entry.substringBefore(':', missingDelimiterValue = "").trim()
    val id = entry.substringAfter(':').trim()

    if (platform.isEmpty()) {
        id.toLongOrNull() ?: error("$env contains [$entry], which is not a numeric telegram id")
        return UserRef(Platform.TELEGRAM, id).key
    }

    val known =
        Platform.entries.firstOrNull { it.name.equals(platform, ignoreCase = true) }
            ?: error("$env contains [$entry], whose platform is not one of ${Platform.entries}")

    require(id.isNotEmpty()) { "$env contains [$entry], which names a platform but no id" }

    return UserRef(known, id).key
}
