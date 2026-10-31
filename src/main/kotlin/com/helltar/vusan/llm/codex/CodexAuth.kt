package com.helltar.vusan.llm.codex

import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.rethrowIfCancellation
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
import kotlinx.serialization.json.*
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.util.*
import kotlin.io.path.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The OAuth client the Codex CLI registers with: a public id baked into the binary, not a secret. The refresh
 * endpoint only accepts refresh tokens minted for it.
 */
private const val CODEX_OAUTH_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
private const val CODEX_TOKEN_URL = "https://auth.openai.com/oauth/token"

// the access token lives ten days, so refreshing a day out leaves dozens of turns to retry a refusal instead
// of one shot that takes the bot down.
private val REFRESH_WINDOW = 24.hours

// within this last stretch the token is as good as gone, and a failed refresh is the operator's problem.
private val EXPIRY_GRACE = 5.minutes

// the endpoint refuses a refresh before `earliest_refresh_at`, so a failure waits instead of repeating on
// every message.
private val REFRESH_RETRY_INTERVAL = 1.hours

// a file rewritten within the clock tick of the last read may keep its stamp and size, so a read that fresh
// is not remembered
private val STAMP_SETTLE_TIME = 2.seconds

private const val REFRESH_ERROR_MAX_CHARS = 500
private val OWNER_ONLY_PERMISSIONS = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

private val log = KotlinLogging.logger {}

private val authJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = true
    }

/**
 * A signed-in ChatGPT session as the LLM client needs it: the bearer token and the workspace account it
 * belongs to, which routes the request to the right subscription. Both go on every request.
 */
data class CodexCredentials(
    val accessToken: String,
    val accountId: String?,
)

/** A failure the operator has to act on, as opposed to a transient error. */
class CodexAuthException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

private fun codexAuthError(message: String, cause: Throwable? = null): Nothing =
    throw CodexAuthException(message, cause)

@Serializable
private data class CodexTokens(
    @SerialName("id_token") val idToken: String = "",
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("account_id") val accountId: String? = null,
)

private data class CodexAuthSnapshot(
    val root: JsonObject,
    val tokens: CodexTokens,
    val fingerprint: String,
)

private data class CachedCodexTokens(
    val tokens: CodexTokens,
    val sourceFingerprint: String,
)

private data class AuthFileStamp(val modifiedAt: Instant, val size: Long) {
    fun settled(): Boolean = modifiedAt.isBefore(Instant.now().minusSeconds(STAMP_SETTLE_TIME.inWholeSeconds))
}

private data class ReadAuthFile(val stamp: AuthFileStamp, val snapshot: CodexAuthSnapshot)

/**
 * No defaults on purpose: the shared client encodes with `encodeDefaults = false`, so a defaulted
 * `grant_type` would be dropped and the endpoint would answer `missing_required_parameter`, a 400 that looks
 * like a dead session.
 */
@Serializable
private data class CodexRefreshRequest(
    @SerialName("client_id") val clientId: String,
    @SerialName("grant_type") val grantType: String,
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
private data class CodexRefreshResponse(
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    // when the endpoint will accept the next refresh; logged rather than honored until a real value shows its
    // shape.
    @SerialName("earliest_refresh_at") val earliestRefreshAt: JsonElement? = null,
)

/**
 * Owns the ChatGPT credentials written by `codex login`.
 *
 * Reads file-backed `auth.json`, hands out the access token and refreshes it near expiry against the CLI's
 * endpoint. Login, logout and device-code stay with the CLI; keyring-backed credentials are unreadable
 * outside it and unsupported.
 */
class CodexAuthStore(
    private val http: HttpClient,
    private val authFile: Path = defaultCodexAuthFile(),
) {

    /** How much of the subscription the bot has drawn, chat and pictures alike. */
    val limits = CodexLimits()

    private val mutex = Mutex()
    private var lastRead: ReadAuthFile? = null
    private var cached: CachedCodexTokens? = null
    private var lastRefreshFailure: Instant? = null

    /**
     * The current access token, refreshed first within [REFRESH_WINDOW] of expiry. Behind a mutex so
     * concurrent turns fire one refresh: the endpoint rate-limits them, and `auth.json` gets a single writer.
     */
    suspend fun credentials(): CodexCredentials =
        mutex.withLock {
            val fresh = refreshedIfDue(currentTokens())

            CodexCredentials(
                accessToken = fresh.accessToken,
                accountId = fresh.accountId ?: fresh.idToken.claimString("chatgpt_account_id"),
            )
        }

    /**
     * The tokens for this request, refreshed first inside [REFRESH_WINDOW].
     *
     * A failed refresh while the token still has days left is a warning: the endpoint refuses refreshes too
     * soon after the previous one, and "sign in again" would send the operator to fix a working session.
     */
    private suspend fun refreshedIfDue(snapshot: CodexAuthSnapshot): CodexTokens {
        val tokens = snapshot.tokens

        if (!tokens.accessToken.expiresWithin(REFRESH_WINDOW))
            return tokens

        val fatal = tokens.accessToken.expiresWithin(EXPIRY_GRACE)
        val refreshToken = tokens.refreshToken?.takeIf { it.isNotBlank() }

        if (refreshToken == null) {
            val message =
                "The Codex access token in [$authFile] is expiring and cannot be refreshed. " +
                        "Rotate it with `codex login --with-access-token` or sign in again."

            if (fatal) codexAuthError(message)

            log.warn { message }
            return tokens
        }

        if (!fatal && !retryDue())
            return tokens

        return runCatching { refresh(snapshot, refreshToken) }
            .onSuccess { lastRefreshFailure = null }
            .getOrElse { e ->
                e.rethrowIfCancellation()
                lastRefreshFailure = Instant.now()

                if (fatal) throw e

                log.warn { "Codex: token refresh failed while the current token is still usable: ${e.message}" }
                tokens
            }
    }

    private fun retryDue(): Boolean =
        lastRefreshFailure?.let { Instant.now().isAfter(it.plusSeconds(REFRESH_RETRY_INTERVAL.inWholeSeconds)) } ?: true

    /** The account's plan for the startup log; absent without the claim. */
    suspend fun planType(): String? =
        mutex.withLock { currentTokens().tokens.idToken.claimString("chatgpt_plan_type") }

    // re-read whenever the file's stamp moves, so login, logout, workspace changes and CLI refreshes show up
    // without a restart. the cache only carries a token refreshed in memory whose write-back failed, usable
    // while the file is unchanged.
    private fun currentTokens(): CodexAuthSnapshot {
        val snapshot = snapshotIfChanged()
        val remembered = cached?.takeIf { it.sourceFingerprint == snapshot.fingerprint }

        return if (remembered != null) {
            snapshot.copy(tokens = remembered.tokens)
        } else {
            snapshot.also { cached = CachedCodexTokens(it.tokens, it.fingerprint) }
        }
    }

    private fun snapshotIfChanged(): CodexAuthSnapshot {
        // taken before the read, so a write landing in between is read again next time
        val stamp = authFileStampOrNull()
        lastRead?.takeIf { stamp != null && it.stamp == stamp }?.let { return it.snapshot }

        val snapshot = readSnapshot()
        lastRead = stamp?.takeIf { it.settled() }?.let { ReadAuthFile(it, snapshot) }

        return snapshot
    }

    private fun authFileStampOrNull(): AuthFileStamp? =
        runCatching { Files.readAttributes(authFile, BasicFileAttributes::class.java) }
            .map { AuthFileStamp(modifiedAt = it.lastModifiedTime().toInstant(), size = it.size()) }
            .getOrNull()

    private fun readSnapshot(): CodexAuthSnapshot {
        if (!authFile.isReadable())
            codexAuthError(
                "Not signed in to ChatGPT: [$authFile] does not exist or is not readable. " +
                        "Run `codex login` on this host as the user that runs Vusan. If the CLI keeps its " +
                        "credentials in the OS keyring, switch it back with `cli_auth_credentials_store = \"file\"`.",
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
                        "[$authFile] has no ChatGPT tokens. Run `codex login` on this host.",
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
                    // the failure body carries the classification, so keep it instead of letting the shared
                    // validator reduce it to a status.
                    expectSuccess = false
                    contentType(ContentType.Application.Json)
                    headers { codexCloudflareHeaders().forEach { (name, value) -> set(name, value) } }
                    setBody(
                        CodexRefreshRequest(
                            clientId = CODEX_OAUTH_CLIENT_ID,
                            grantType = "refresh_token",
                            refreshToken = refreshToken,
                        ),
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

        log.info { "Codex: ChatGPT access token refreshed, earliest_refresh_at=[${body.earliestRefreshAt}]" }

        val refreshed =
            snapshot.tokens.copy(
                idToken = body.idToken?.takeIf { it.isNotBlank() } ?: snapshot.tokens.idToken,
                accessToken = accessToken,
                // the endpoint rotates the refresh token; losing the new one strands the next refresh
                refreshToken = body.refreshToken?.takeIf { it.isNotBlank() } ?: refreshToken,
            )

        val persistedFingerprint = persist(refreshed, snapshot.fingerprint)
        cached = CachedCodexTokens(refreshed, persistedFingerprint ?: snapshot.fingerprint)

        return refreshed
    }

    // keep every top-level field the CLI owns, and replace the file only if it is still the version
    // refreshed, so an overlapping login, logout or CLI refresh is not lost.
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
                                                        .jsonObject,
                                        ),
                                "last_refresh" to JsonPrimitive(Instant.now().toString()),
                            ),
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
            // the token in memory still works; only the next restart pays for it.
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

    return when (body?.let { refreshErrorCode(it) }?.lowercase()) {
        "refresh_token_expired", "token_expired" -> "The ChatGPT session has expired. $reLogin"
        "refresh_token_reused" -> "The ChatGPT refresh token was already used. $reLogin"
        "refresh_token_invalidated" -> "The ChatGPT session was revoked. $reLogin"
        "refresh_token_account_mismatch" -> "The ChatGPT session belongs to another sandbox. $reLogin"
        "invalid_grant", "invalid_refresh_token" -> "The ChatGPT refresh token was rejected as invalid. $reLogin"
        // a rejected credential is a 401. anything else is the request refused, most likely too soon after
        // the last refresh, and signing in again would not help.
        else ->
            buildString {
                append(
                    if (status == HttpStatusCode.Unauthorized)
                        "The ChatGPT session was rejected (HTTP 401). $reLogin"
                    else
                        "The token endpoint refused the refresh (HTTP ${status.value}).",
                )
                body?.collapseWhitespaceAndCap(REFRESH_ERROR_MAX_CHARS)?.let { append(" body=[$it]") }
            }
    }
}

// `error` arrives as a bare string, as an object with `code` and `message`, or as a top-level code; reading
// only one logs a dead session as a bare "HTTP 400".
private fun refreshErrorCode(body: String): String? =
    runCatching {
        val root = authJson.parseToJsonElement(body).jsonObject
        val error = root["error"]

        (error as? JsonObject)?.get("code").stringOrNull()
            ?: error.stringOrNull()
            ?: root["error_code"].stringOrNull()
            ?: root["code"].stringOrNull()
    }.getOrNull()

private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNullSafe()

private fun JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()

/** Resolves `auth.json` from `CODEX_HOME`, defaulting as the CLI does. */
internal fun defaultCodexAuthFile(codexHome: String? = null): Path {
    val home = codexHome?.trim()?.takeIf { it.isNotBlank() }

    return if (home != null) Path(home, "auth.json") else Path(System.getProperty("user.home"), ".codex", "auth.json")
}

// `exp` is all that is read off the JWT locally, so the refresh happens ahead of a 401 instead of a user's
// turn finding the token dead.
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
        val decoded = Base64.getUrlDecoder().decode(payload.padBase64())
        authJson.parseToJsonElement(decoded.decodeToString()).jsonObject
    }.getOrNull()

private fun String.padBase64(): String =

    when (length % 4) {
        2 -> "$this=="
        3 -> "$this="
        else -> this
    }
