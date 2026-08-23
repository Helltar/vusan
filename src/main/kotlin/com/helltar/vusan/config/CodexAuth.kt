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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isReadable
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
private val OWNER_ONLY_PERMISSIONS = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

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
private data class CodexTokens(
    @SerialName("id_token") val idToken: String = "",
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("account_id") val accountId: String? = null
)

private data class CodexAuthSnapshot(
    val root: JsonObject,
    val tokens: CodexTokens,
    val fingerprint: String
)

private data class CachedCodexTokens(
    val tokens: CodexTokens,
    val sourceFingerprint: String
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
 * The CLI is the only thing that mints tokens here — this store reads file-backed `auth.json`, hands
 * out the access token, and refreshes it against the same endpoint the CLI uses once it is close to
 * expiry. Login, logout and device-code stay the CLI's job; keyring-backed credentials are not
 * readable outside the CLI and therefore are not supported by this direct HTTP bridge.
 */
class CodexAuthStore(
    private val http: HttpClient,
    private val authFile: Path = defaultCodexAuthFile()
) {

    private val mutex = Mutex()
    private var cached: CachedCodexTokens? = null

    /**
     * The current access token, refreshed first when it is within [REFRESH_WINDOW] of expiring.
     *
     * Serialized on a mutex: a burst of concurrent turns must not fire a refresh each, because the
     * endpoint invalidates a refresh token once it is used and the losers would all fail.
     */
    suspend fun credentials(): CodexCredentials =
        mutex.withLock {
            val snapshot = currentTokens()
            val tokens = snapshot.tokens
            val fresh =
                if (tokens.accessToken.expiresWithin(REFRESH_WINDOW)) {
                    val refreshToken =
                        tokens.refreshToken?.takeIf { it.isNotBlank() }
                            ?: codexAuthError(
                                "The Codex access token in [$authFile] is expiring and cannot be refreshed. " +
                                        "Rotate it with `codex login --with-access-token` or sign in again."
                            )

                    refresh(snapshot, refreshToken)
                } else {
                    tokens
                }

            CodexCredentials(
                accessToken = fresh.accessToken,
                accountId = fresh.accountId ?: fresh.idToken.claimString("chatgpt_account_id")
            )
        }

    /** Plan on the signed-in account, for the startup log. Absent when the claim is missing. */
    suspend fun planType(): String? =
        mutex.withLock { currentTokens().tokens.idToken.claimString("chatgpt_plan_type") }

    // reread the file on every request so `codex login`, `codex logout`, workspace changes and a CLI
    // refresh become visible without restarting vusan. the cached entry only carries a token we refreshed
    // in memory when writing it back failed; it remains usable while the on-disk source is unchanged.
    private fun currentTokens(): CodexAuthSnapshot {
        val snapshot = readSnapshot()
        val remembered = cached?.takeIf { it.sourceFingerprint == snapshot.fingerprint }

        return if (remembered != null) {
            snapshot.copy(tokens = remembered.tokens)
        } else {
            snapshot.also { cached = CachedCodexTokens(it.tokens, it.fingerprint) }
        }
    }

    private fun readSnapshot(): CodexAuthSnapshot {
        if (!authFile.isReadable())
            codexAuthError(
                "Not signed in to ChatGPT: [$authFile] does not exist or is not readable. " +
                        "Configure Codex with `cli_auth_credentials_store = \"file\"` and run `codex login` on this host."
            )

        val content =
            runCatching { authFile.readText() }
                .getOrElse { codexAuthError("Could not read [$authFile]. Run `codex login` again.", it) }

        val root =
            runCatching { authJson.parseToJsonElement(content).jsonObject }
                .getOrElse { codexAuthError("Could not parse [$authFile]. Run `codex login` again.", it) }

        val tokenElement =
            root["tokens"]
                ?: codexAuthError(
                    if ((root["OPENAI_API_KEY"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true)
                        "[$authFile] holds an API key, not a ChatGPT session. " +
                                "Run `codex logout` then `codex login` to sign in with ChatGPT, " +
                                "or set LLM_PROVIDER=openai with LLM_API_KEY to use the API key directly."
                    else
                        "[$authFile] has no ChatGPT tokens. Run `codex login` on this host."
                )

        val tokens =
            runCatching { authJson.decodeFromJsonElement(CodexTokens.serializer(), tokenElement) }
                .getOrElse { codexAuthError("Could not parse the ChatGPT tokens in [$authFile].", it) }

        if (tokens.accessToken.isBlank())
            codexAuthError("[$authFile] has no usable ChatGPT access token. Run `codex login` again.")

        return CodexAuthSnapshot(root, tokens, content.fingerprint())
    }

    private suspend fun refresh(snapshot: CodexAuthSnapshot, refreshToken: String): CodexTokens {
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
                            refreshToken = refreshToken
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
            snapshot.tokens.copy(
                idToken = body.idToken?.takeIf { it.isNotBlank() } ?: snapshot.tokens.idToken,
                accessToken = accessToken,
                // the endpoint rotates the refresh token; dropping the new one strands the next refresh
                refreshToken = body.refreshToken?.takeIf { it.isNotBlank() } ?: refreshToken
            )

        val persistedFingerprint = persist(refreshed, snapshot.fingerprint)
        cached = CachedCodexTokens(refreshed, persistedFingerprint ?: snapshot.fingerprint)

        return refreshed
    }

    // preserve every top-level field the CLI owns and replace the file only if it is still the version
    // we refreshed. this prevents an overlapping `codex login`, logout or CLI refresh from being lost.
    private fun persist(tokens: CodexTokens, expectedFingerprint: String): String? {
        var temp: Path? = null

        return runCatching {
            val current = readSnapshot()

            if (current.fingerprint != expectedFingerprint) {
                log.warn { "Codex: [$authFile] changed during token refresh; leaving the newer file untouched" }
                return@runCatching null
            }

            val currentTokenFields = checkNotNull(current.root["tokens"] as? JsonObject)
            val updated =
                JsonObject(
                    current.root +
                            mapOf(
                                "tokens" to
                                        JsonObject(
                                            currentTokenFields +
                                                    authJson
                                                        .encodeToJsonElement(CodexTokens.serializer(), tokens)
                                                        .jsonObject
                                        ),
                                "last_refresh" to JsonPrimitive(Instant.now().toString())
                            )
                )
            val content = authJson.encodeToString(JsonObject.serializer(), updated)
            val parent = authFile.toAbsolutePath().parent
            parent.createDirectories()

            temp = createSecureTempFile(parent, authFile.fileName.toString())
            temp.writeText(content)
            setOwnerOnlyPermissions(temp)
            moveReplacing(temp, authFile)

            content.fingerprint()
        }.onFailure {
            // the in-memory token is still good, so the turn can proceed; only the next restart pays for it.
            log.warn { "Codex: refreshed token could not be written back to [$authFile]: ${it.message}" }
        }.getOrNull().also {
            temp?.let { path -> runCatching { Files.deleteIfExists(path) } }
        }
    }
}

private fun createSecureTempFile(parent: Path, authFileName: String): Path {
    val prefix = ".$authFileName.vusan-"
    val posix = Files.getFileAttributeView(parent, PosixFileAttributeView::class.java)

    return if (posix != null)
        Files.createTempFile(parent, prefix, ".tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY_PERMISSIONS))
    else
        Files.createTempFile(parent, prefix, ".tmp")
}

private fun setOwnerOnlyPermissions(path: Path) {
    if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null)
        Files.setPosixFilePermissions(path, OWNER_ONLY_PERMISSIONS)
}

private fun moveReplacing(source: Path, target: Path) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun String.fingerprint(): String =
    Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(toByteArray()))

private fun refreshFailureMessage(status: HttpStatusCode, body: String?): String {
    val reLogin = "Run `codex logout` then `codex login` on this host."

    return when (body?.let { refreshErrorCode(it) }) {
        "refresh_token_expired" -> "The ChatGPT session has expired. $reLogin"
        "refresh_token_reused" -> "The ChatGPT refresh token was already used. $reLogin"
        "refresh_token_invalidated" -> "The ChatGPT session was revoked. $reLogin"
        "refresh_token_account_mismatch" -> "The ChatGPT session belongs to another workspace. $reLogin"
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

/** Resolves `auth.json` from the configured `CODEX_HOME`, with the CLI default as fallback. */
internal fun defaultCodexAuthFile(codexHome: String? = null): Path {
    val home = codexHome?.trim()?.takeIf { it.isNotBlank() }
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
