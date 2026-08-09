package com.helltar.vusan.config

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.isReadable
import kotlin.io.path.moveTo
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

/**
 * The OAuth client the Codex CLI itself registers with. It is a public identifier baked into the
 * released binary, not a secret, and the refresh endpoint only accepts refresh tokens minted for it —
 * so a token written by `codex login` can only be refreshed under this id.
 */
private const val CODEX_OAUTH_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
private const val CODEX_TOKEN_URL = "https://auth.openai.com/oauth/token"

// codex refreshes 5 minutes before expiry; matching it keeps the two from disagreeing about whether a
// shared auth.json is still fresh.
private val REFRESH_WINDOW = 5.minutes

private val log = KotlinLogging.logger {}

private val authJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = true
    }

/**
 * A signed-in ChatGPT session, as far as the LLM client is concerned: the bearer token and the
 * workspace it belongs to. Both go on every request; the account id is what routes a workspace
 * subscription to the right entitlement.
 */
data class CodexCredentials(
    val accessToken: String,
    val accountId: String?
)

/** Every failure that means "the operator has to do something", as opposed to a transient error. */
class CodexAuthException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

private fun codexAuthError(message: String, cause: Throwable? = null): Nothing =
    throw CodexAuthException(message, cause)

@Serializable
private data class CodexAuthFile(
    @SerialName("OPENAI_API_KEY") val openAiApiKey: String? = null,
    val tokens: CodexTokens? = null,
    @SerialName("last_refresh") val lastRefresh: String? = null
)

@Serializable
private data class CodexTokens(
    @SerialName("id_token") val idToken: String = "",
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("account_id") val accountId: String? = null
)

@Serializable
private data class CodexRefreshRequest(
    @SerialName("client_id") val clientId: String,
    @SerialName("grant_type") val grantType: String = "refresh_token",
    @SerialName("refresh_token") val refreshToken: String
)

@Serializable
private data class CodexRefreshResponse(
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null
)

/**
 * Owns the ChatGPT credentials written by `codex login`.
 *
 * The CLI is the only thing that mints tokens here — this store reads `auth.json`, hands out the
 * access token, and refreshes it against the same endpoint the CLI uses once it is close to expiry.
 * Login, logout, device-code and keyring storage all stay the CLI's job.
 */
class CodexAuthStore(
    private val http: HttpClient,
    private val authFile: Path = defaultCodexAuthFile()
) {

    private val mutex = Mutex()
    private var cached: CodexTokens? = null

    /**
     * The current access token, refreshed first when it is within [REFRESH_WINDOW] of expiring.
     *
     * Serialized on a mutex: a burst of concurrent turns must not fire a refresh each, because the
     * endpoint invalidates a refresh token once it is used and the losers would all fail.
     */
    suspend fun credentials(): CodexCredentials =
        mutex.withLock {
            val tokens = cached ?: readTokens().also { cached = it }
            val fresh = if (tokens.accessToken.expiresWithin(REFRESH_WINDOW)) refresh(tokens) else tokens

            CodexCredentials(
                accessToken = fresh.accessToken,
                accountId = fresh.accountId ?: fresh.idToken.claimString("chatgpt_account_id")
            )
        }

    /** Plan on the signed-in account, for the startup log. Absent when the claim is missing. */
    suspend fun planType(): String? =
        mutex.withLock { (cached ?: readTokens().also { cached = it }).idToken.claimString("chatgpt_plan_type") }

    private fun readTokens(): CodexTokens {
        if (!authFile.isReadable())
            codexAuthError(
                "Not signed in to ChatGPT: [$authFile] does not exist or is not readable. Run `codex login` on this host."
            )

        val parsed =
            runCatching { authJson.decodeFromString<CodexAuthFile>(authFile.readText()) }
                .getOrElse { codexAuthError("Could not parse [$authFile]. Run `codex login` again.", it) }

        val tokens =
            parsed.tokens
                ?: codexAuthError(
                    if (parsed.openAiApiKey != null)
                        "[$authFile] holds an API key, not a ChatGPT session. " +
                                "Run `codex logout` then `codex login` to sign in with ChatGPT, " +
                                "or set LLM_PROVIDER=openai with LLM_API_KEY to use the API key directly."
                    else
                        "[$authFile] has no ChatGPT tokens. Run `codex login` on this host."
                )

        if (tokens.accessToken.isBlank() || tokens.refreshToken.isBlank())
            codexAuthError("[$authFile] has no usable ChatGPT tokens. Run `codex login` again.")

        return tokens
    }

    private suspend fun refresh(tokens: CodexTokens): CodexTokens {
        log.info { "Codex: refreshing the ChatGPT access token" }

        val response =
            runCatching {
                http.post(CODEX_TOKEN_URL) {
                    // the body carries the classification we need on failure, so keep the raw response
                    // instead of letting the shared validator collapse it into a status-only error.
                    expectSuccess = false
                    contentType(ContentType.Application.Json)
                    setBody(
                        CodexRefreshRequest(
                            clientId = CODEX_OAUTH_CLIENT_ID,
                            refreshToken = tokens.refreshToken
                        )
                    )
                }
            }.getOrElse {
                codexAuthError("Could not reach $CODEX_TOKEN_URL to refresh the ChatGPT session.", it)
            }

        if (!response.status.isSuccess())
            codexAuthError(refreshFailureMessage(response.status, runCatching { response.bodyAsText() }.getOrNull()))

        val body =
            runCatching { response.body<CodexRefreshResponse>() }
                .getOrElse { codexAuthError("Unexpected reply from $CODEX_TOKEN_URL.", it) }

        val accessToken =
            body.accessToken?.takeIf { it.isNotBlank() }
                ?: codexAuthError("$CODEX_TOKEN_URL returned no access token. Run `codex login` again.")

        val refreshed =
            tokens.copy(
                idToken = body.idToken?.takeIf { it.isNotBlank() } ?: tokens.idToken,
                accessToken = accessToken,
                // the endpoint rotates the refresh token; dropping the new one strands the next refresh
                refreshToken = body.refreshToken?.takeIf { it.isNotBlank() } ?: tokens.refreshToken
            )

        cached = refreshed
        persist(refreshed)

        return refreshed
    }

    // write the rotated tokens back so a bot restart does not have to burn another refresh, and so the
    // codex CLI on this host sees the same session. atomic rename keeps a crash mid-write from leaving
    // a truncated auth.json behind, which would read as "not signed in".
    private fun persist(tokens: CodexTokens) {
        runCatching {
            val existing =
                runCatching { authJson.decodeFromString<CodexAuthFile>(authFile.readText()) }
                    .getOrDefault(CodexAuthFile())

            val temp = authFile.resolveSibling("${authFile.fileName}.vusan.tmp")
            temp.createParentDirectories()
            temp.writeText(
                authJson.encodeToString(
                    existing.copy(tokens = tokens, lastRefresh = Instant.now().toString())
                )
            )
            temp.moveTo(authFile, overwrite = true)
        }.onFailure {
            // the in-memory token is still good, so the turn can proceed; only the next restart pays for it.
            log.warn { "Codex: refreshed token could not be written back to [$authFile]: ${it.message}" }
        }
    }
}

private fun refreshFailureMessage(status: HttpStatusCode, body: String?): String {
    val reLogin = "Run `codex logout` then `codex login` on this host."

    return when (body?.let { refreshErrorCode(it) }) {
        "refresh_token_expired" -> "The ChatGPT session has expired. $reLogin"
        "refresh_token_reused" -> "The ChatGPT refresh token was already used. $reLogin"
        "refresh_token_invalidated" -> "The ChatGPT session was revoked. $reLogin"
        else -> "Could not refresh the ChatGPT session (HTTP ${status.value}). $reLogin"
    }
}

private fun refreshErrorCode(body: String): String? =
    runCatching {
        val root = authJson.parseToJsonElement(body).jsonObject
        (root["error"]?.jsonPrimitive?.contentOrNullSafe()) ?: root["error_code"]?.jsonPrimitive?.contentOrNullSafe()
    }.getOrNull()

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()

/** `CODEX_HOME` wins so a container can mount the credentials wherever it likes, matching the CLI. */
internal fun defaultCodexAuthFile(): Path {
    val home = System.getenv("CODEX_HOME")?.takeIf { it.isNotBlank() }
    return if (home != null) Path(home, "auth.json") else Path(System.getProperty("user.home"), ".codex", "auth.json")
}

// the access token is a JWT; `exp` is the only thing worth reading off it locally, and reading it is
// what lets us refresh ahead of a 401 instead of failing a user's turn to discover the token died.
internal fun String.expiresWithin(window: kotlin.time.Duration): Boolean {
    val exp = jwtClaims()?.get("exp")?.jsonPrimitive?.longOrNull ?: return true
    return Instant.ofEpochSecond(exp).isBefore(Instant.now().plusSeconds(window.inWholeSeconds))
}

internal fun String.claimString(name: String): String? {
    val claims = jwtClaims() ?: return null
    val auth = claims["https://api.openai.com/auth"] as? JsonObject ?: return null
    return auth[name]?.jsonPrimitive?.contentOrNullSafe()?.takeIf { it.isNotBlank() }
}

private fun String.jwtClaims(): JsonObject? =
    runCatching {
        val payload = split('.').getOrNull(1) ?: return null
        val decoded = java.util.Base64.getUrlDecoder().decode(payload.padBase64())
        authJson.parseToJsonElement(decoded.decodeToString()).jsonObject
    }.getOrNull()

private fun String.padBase64(): String =
    when (length % 4) {
        2 -> "$this=="
        3 -> "$this="
        else -> this
    }
