package com.helltar.vusan.config

import com.helltar.vusan.agent.AgentFactory
import com.helltar.vusan.llm.ReasoningEffort
import com.helltar.vusan.llm.openai.OpenAiEndpoint
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
    val addressing: AddressingConfig? = null,
    val agentMaxModelCalls: Int,
    val appearance: String?,
    val chatHistory: ConversationConfig = ConversationConfig(),
    val databasePath: String,
    val diaryEnabled: Boolean = true,
    val elevenLabsApiKey: String?,
    val elevenLabsTts: ElevenLabsTtsConfig?,
    val giphyApiKey: String?,
    val klipyApiKey: String?,
    val groupLog: GroupLogConfig = GroupLogConfig(),
    val initiative: InitiativeConfig? = null,
    val llmProvider: LlmProviderConfig,
    val llmFallback: LlmProviderConfig? = null,
    val maxConcurrentTurns: Int,
    val openAiImage: OpenAiImageConfig?,
    val openAiImageApiKey: String?,
    val openAiStt: OpenAiSttConfig?,
    val vision: LlmProviderConfig? = null,
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
        require(agentMaxModelCalls >= AgentFactory.MIN_MODEL_CALLS) { "AGENT_MAX_MODEL_CALLS must be at least ${AgentFactory.MIN_MODEL_CALLS}" }
        require(maxConcurrentTurns > 0) { "MAX_CONCURRENT_TURNS must be positive" }
        require(regolithUrl == null || !regolithToken.isNullOrBlank()) { "Sandbox API authentication is required" }
        // the classifier reads the lines around a message, and the bot's own recent ones, from the transcript
        require(addressing == null || groupLog.enabled) { "ADDRESSING_ENABLED needs the group log, which GROUP_LOG_ENABLED=false turns off" }
    }

    companion object {
        private const val DEFAULT_AGENT_MAX_MODEL_CALLS = 100
        private const val DEFAULT_LLM_REQUEST_TIMEOUT_SECONDS = 300L
        private const val LLM_PREFIX = "LLM"
        private const val LLM_FALLBACK_PREFIX = "LLM_FALLBACK"
        private const val VISION_PREFIX = "VISION"
        private const val ADDRESSING_PREFIX = "ADDRESSING"
        private const val DEFAULT_MAX_CONCURRENT_TURNS = 8

        private val dotenv = dotenv { ignoreIfMissing = true }

        private val log = KotlinLogging.logger {}

        fun fromEnv(): AppConfig {
            val elevenLabsKey = readEnv("ELEVENLABS_API_KEY")
            val openAiImageKey = readEnv("OPENAI_IMAGE_API_KEY")
            val llmProvider = resolveLlmProvider(LLM_PREFIX, fallbackTimeout = null)
            val llmFallback = resolveLlmFallback(llmProvider)
            val imageRoute = resolveImageRoute(openAiImageKey != null, llmProvider)
            val regolithUrl = readEnv("REGOLITH_URL")

            return AppConfig(
                accessPolicy = AccessPolicy(allowed = readIdSetEnv("ALLOWED_IDS"), banned = readIdSetEnv("BANNED_IDS")),
                addressing = resolveAddressing(llmProvider),
                agentMaxModelCalls = readIntEnv("AGENT_MAX_MODEL_CALLS") ?: DEFAULT_AGENT_MAX_MODEL_CALLS,
                appearance = resolveAppearance(),
                databasePath = readEnv("DB_FILE") ?: "data/db/vusan.db",
                diaryEnabled = readBooleanEnv("DIARY_ENABLED") ?: true,
                elevenLabsApiKey = elevenLabsKey,
                giphyApiKey = readEnv("GIPHY_API_KEY"),
                klipyApiKey = readEnv("KLIPY_API_KEY"),
                initiative = resolveInitiative(),
                llmProvider = llmProvider,
                llmFallback = llmFallback,
                maxConcurrentTurns = readIntEnv("MAX_CONCURRENT_TURNS") ?: DEFAULT_MAX_CONCURRENT_TURNS,
                openAiImageApiKey = openAiImageKey,
                openAiStt = resolveOpenAiStt(),
                vision = resolveRole(VISION_PREFIX, llmProvider),
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
            )
        }

        // answering without a mention changes how the bot behaves in a group and was measured on small openai
        // models only, so it waits for the operator's switch; it never runs on the chat model, so the switch
        // needs a model, and a model set without the switch is named rather than silently ignored.
        private fun resolveAddressing(chat: LlmProviderConfig): AddressingConfig? {
            if (readBooleanEnv("ADDRESSING_ENABLED") != true) {
                if (readEnv("ADDRESSING_MODEL") != null) {
                    log.warn { "ADDRESSING_MODEL is set but ADDRESSING_ENABLED is not true, so answering without a mention stays off" }
                }

                return null
            }

            val provider =
                requireNotNull(resolveRole(ADDRESSING_PREFIX, chat)) {
                    "ADDRESSING_MODEL is required when ADDRESSING_ENABLED is true: the verdicts run on a model of their own"
                }

            return AddressingConfig(
                provider = provider,
                names = readEnv("ADDRESSING_NAMES")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
            )
        }

        // a model for one job — looking at pictures, judging whether a message was for the bot — named under
        // its own prefix. `<ROLE>_MODEL` is the switch; without `<ROLE>_PROVIDER` the model runs on the chat
        // provider with its key, and with it the role is a provider of its own, read like the chat one.
        private fun resolveRole(prefix: String, chat: LlmProviderConfig): LlmProviderConfig? {
            val model = readEnv("${prefix}_MODEL") ?: return null

            if (readEnv("${prefix}_PROVIDER") != null) return resolveLlmProvider(prefix, fallbackTimeout = chat.requestTimeout)

            return chat.withModel(
                model = model,
                reasoningEffort = resolveReasoningEffort(prefix),
                contextWindowTokens = readPositiveLongEnv("${prefix}_CONTEXT_WINDOW_TOKENS"),
                envPrefix = prefix,
            )
        }

        // the switch is the only setting, and it is on unless turned off: how often it looks and how much
        // it says are the feature's own numbers, and live with it.
        private fun resolveInitiative(): InitiativeConfig? =
            InitiativeConfig().takeIf { readBooleanEnv("INITIATIVE_ENABLED") != false }

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

            val contextWindowTokens = readPositiveLongEnv("${prefix}_CONTEXT_WINDOW_TOKENS")

            val requestTimeout =
                readPositiveLongEnv("${prefix}_REQUEST_TIMEOUT_SECONDS")?.seconds
                    ?: fallbackTimeout
                    ?: DEFAULT_LLM_REQUEST_TIMEOUT_SECONDS.seconds

            val provider = raw.trim().lowercase()

            if (provider == "codex") {
                return LlmProviderConfig.Codex(
                    model = requireEnv("${prefix}_MODEL"),
                    reasoningEffort = resolveReasoningEffort(prefix),
                    // the tier is the chat's: a role spends the same allowance, and its model may not be served at it
                    serviceTier = resolveCodexServiceTier().takeIf { prefix == LLM_PREFIX || prefix == LLM_FALLBACK_PREFIX },
                    imageGeneration = readBooleanEnv("CODEX_IMAGE_GENERATION_ENABLED") ?: true,
                    webSearch = readBooleanEnv("CODEX_WEB_SEARCH_ENABLED") ?: true,
                    clientVersion = resolveCodexClientVersion(),
                    authFile = defaultCodexAuthFile(readEnv("CODEX_HOME")),
                    requestTimeout = requestTimeout,
                    contextWindowTokens = contextWindowTokens,
                    envPrefix = prefix,
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
                    envPrefix = prefix,
                )
            }

            return when (provider) {
                "openai" ->
                    LlmProviderConfig.OpenAi(
                        apiKey = requireEnv("${prefix}_API_KEY"),
                        model = requireEnv("${prefix}_MODEL"),
                        reasoningEffort = resolveReasoningEffort(prefix),
                        requestTimeout = requestTimeout,
                        contextWindowTokens = contextWindowTokens,
                        envPrefix = prefix,
                    )

                "anthropic" ->
                    LlmProviderConfig.Anthropic(
                        apiKey = requireEnv("${prefix}_API_KEY"),
                        model = requireEnv("${prefix}_MODEL"),
                        reasoningEffort = resolveReasoningEffort(prefix),
                        requestTimeout = requestTimeout,
                        contextWindowTokens = contextWindowTokens,
                        envPrefix = prefix,
                    )

                else ->
                    error("Unsupported ${prefix}_PROVIDER=[$provider]. Supported values: openai, anthropic, openai-compatible, codex")
            }
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

        // the catalog Codex answers with is filtered by the claimed client version, and this pins it: for
        // a host that cannot ask github for the latest release, or when the automatic choice is wrong.
        private fun resolveCodexClientVersion(): String? {
            val raw = readEnv("CODEX_CLIENT_VERSION")?.trim() ?: return null

            return raw.takeIf { CODEX_VERSION.matches(it) }
                ?: error("Unsupported CODEX_CLIENT_VERSION=[$raw]. Expected a Codex CLI version such as 0.162.0")
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

        private fun readPositiveLongEnv(env: String): Long? = parsePositiveLongEnv(env, readEnv(env))

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

internal fun parsePositiveLongEnv(env: String, raw: String?): Long? =
    parseLongEnv(env, raw)?.also { require(it > 0L) { "$env=[$it] must be positive" } }

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
