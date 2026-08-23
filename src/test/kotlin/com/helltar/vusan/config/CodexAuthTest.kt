package com.helltar.vusan.config

import com.helltar.vusan.infra.Http
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.util.Base64
import kotlin.io.path.deleteExisting
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class CodexAuthTest {

    @Test
    fun `credentials read the access token and account id from auth json`() = runBlocking {
        val file = authFile(accessToken = jwt(expiresInMinutes = 60), accountId = "acct-42")
        val store = CodexAuthStore(Http.createClient(MockEngine { error("no refresh expected") }), file)

        val credentials = store.credentials()

        assertEquals("acct-42", credentials.accountId)
        assertTrue(credentials.accessToken.isNotBlank())
    }

    @Test
    fun `account id falls back to the id token claim when the field is absent`() = runBlocking {
        val file =
            authFile(
                accessToken = jwt(expiresInMinutes = 60),
                idToken = jwt(expiresInMinutes = 60, accountId = "acct-from-claim"),
                accountId = null
            )
        val store = CodexAuthStore(Http.createClient(MockEngine { error("no refresh expected") }), file)

        assertEquals("acct-from-claim", store.credentials().accountId)
    }

    @Test
    fun `a missing auth file reports that nobody signed in`() = runBlocking {
        val store =
            CodexAuthStore(
                Http.createClient(MockEngine { error("unreachable") }),
                Files.createTempDirectory("codex").resolve("auth.json")
            )

        val error = assertFailsWith<CodexAuthException> { store.credentials() }
        assertTrue("codex login" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `an api-key-only auth file points at the sign-in it is missing`() = runBlocking {
        val file = Files.createTempDirectory("codex").resolve("auth.json")
        file.writeText("""{"OPENAI_API_KEY":"sk-test"}""")

        val store = CodexAuthStore(Http.createClient(MockEngine { error("unreachable") }), file)

        val error = assertFailsWith<CodexAuthException> { store.credentials() }
        assertTrue("API key" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `an expiring token is refreshed and the rotated refresh token is written back`() = runBlocking {
        val file = authFile(accessToken = jwt(expiresInMinutes = 1), refreshToken = "refresh-old")
        val original = Json.parseToJsonElement(file.readText()).jsonObject
        val tokens = original.getValue("tokens").jsonObject
        file.writeText(
            JsonObject(
                original +
                        mapOf(
                            "auth_mode" to JsonPrimitive("chatgpt"),
                            "tokens" to JsonObject(tokens + ("future_token_field" to JsonPrimitive("keep-me")))
                        )
            ).toString()
        )
        if (Files.getFileAttributeView(file, PosixFileAttributeView::class.java) != null) {
            Files.setPosixFilePermissions(file, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }
        var refreshCalls = 0

        val http =
            Http.createClient(
                MockEngine { request ->
                    refreshCalls++
                    assertEquals("auth.openai.com", request.url.host)
                    assertEquals("/oauth/token", request.url.encodedPath)

                    respond(
                        content =
                            ByteReadChannel(
                                """{"access_token":"${jwt(expiresInMinutes = 60)}","refresh_token":"refresh-new"}"""
                            ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            )

        CodexAuthStore(http, file).credentials()

        assertEquals(1, refreshCalls)

        val persisted = Json.parseToJsonElement(file.readText()).jsonObject["tokens"]?.jsonObject
        assertEquals("refresh-new", persisted?.get("refresh_token")?.jsonPrimitive?.content)

        val root = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals("chatgpt", root["auth_mode"]?.jsonPrimitive?.content)
        assertEquals("keep-me", root["tokens"]?.jsonObject?.get("future_token_field")?.jsonPrimitive?.content)
        if (Files.getFileAttributeView(file, PosixFileAttributeView::class.java) != null) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file)
            )
        }
    }

    @Test
    fun `a fresh token is used without contacting the refresh endpoint`() = runBlocking {
        val file = authFile(accessToken = jwt(expiresInMinutes = 60))
        val store = CodexAuthStore(Http.createClient(MockEngine { error("refresh must not be called") }), file)

        store.credentials()
        assertTrue(store.credentials().accessToken.isNotBlank())
    }

    @Test
    fun `a fresh access-token login works without a refresh token`() = runBlocking {
        val accessToken = jwt(expiresInMinutes = 60)
        val file = authFile(accessToken = accessToken, refreshToken = null)
        val store = CodexAuthStore(Http.createClient(MockEngine { error("refresh must not be called") }), file)

        assertEquals(accessToken, store.credentials().accessToken)
    }

    @Test
    fun `an expiring access-token login asks for rotation`() = runBlocking {
        val file = authFile(accessToken = jwt(expiresInMinutes = 1), refreshToken = null)
        val store = CodexAuthStore(Http.createClient(MockEngine { error("refresh must not be called") }), file)

        val error = assertFailsWith<CodexAuthException> { store.credentials() }

        assertTrue("with-access-token" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `an external login replaces cached credentials without a restart`() = runBlocking {
        val firstToken = jwt(expiresInMinutes = 60)
        val secondToken = jwt(expiresInMinutes = 61)
        val file = authFile(accessToken = firstToken)
        val store = CodexAuthStore(Http.createClient(MockEngine { error("refresh must not be called") }), file)

        assertEquals(firstToken, store.credentials().accessToken)
        file.writeText(authJson(accessToken = secondToken, accountId = "acct-2"))

        val credentials = store.credentials()
        assertEquals(secondToken, credentials.accessToken)
        assertEquals("acct-2", credentials.accountId)
    }

    @Test
    fun `an external logout invalidates cached credentials without a restart`() = runBlocking {
        val file = authFile(accessToken = jwt(expiresInMinutes = 60))
        val store = CodexAuthStore(Http.createClient(MockEngine { error("refresh must not be called") }), file)

        store.credentials()
        file.deleteExisting()

        val error = assertFailsWith<CodexAuthException> { store.credentials() }
        assertTrue("codex login" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `a login racing a refresh is not overwritten`() = runBlocking {
        val file = authFile(accessToken = jwt(expiresInMinutes = 1), refreshToken = "refresh-old")
        val refreshedToken = jwt(expiresInMinutes = 60)
        val externalToken = jwt(expiresInMinutes = 61)

        val http =
            Http.createClient(
                MockEngine {
                    file.writeText(authJson(accessToken = externalToken, accountId = "acct-external"))
                    respond(
                        content = ByteReadChannel("""{"access_token":"$refreshedToken","refresh_token":"refresh-new"}"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            )
        val store = CodexAuthStore(http, file)

        assertEquals(refreshedToken, store.credentials().accessToken)
        val next = store.credentials()
        assertEquals(externalToken, next.accessToken)
        assertEquals("acct-external", next.accountId)
    }

    @Test
    fun `an expired refresh token asks the operator to sign in again`() = runBlocking {
        val file = authFile(accessToken = jwt(expiresInMinutes = 1))

        val http =
            Http.createClient(
                MockEngine {
                    respond(
                        content = ByteReadChannel("""{"error":"refresh_token_expired"}"""),
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            )

        val error = assertFailsWith<CodexAuthException> { CodexAuthStore(http, file).credentials() }

        assertTrue("expired" in error.message.orEmpty(), error.message.orEmpty())
        assertTrue("codex login" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `a revoked refresh token is reported as revoked`() = runBlocking {
        val file = authFile(accessToken = jwt(expiresInMinutes = 1))

        val http =
            Http.createClient(
                MockEngine {
                    respond(
                        content = ByteReadChannel("""{"error":"refresh_token_invalidated"}"""),
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            )

        val error = assertFailsWith<CodexAuthException> { CodexAuthStore(http, file).credentials() }
        assertTrue("revoked" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `expiresWithin treats an unparseable token as already expired`() {
        assertTrue("not-a-jwt".expiresWithin(5.minutes))
    }

    @Test
    fun `expiresWithin reads the exp claim`() {
        assertTrue(jwt(expiresInMinutes = 1).expiresWithin(5.minutes))
        assertTrue(!jwt(expiresInMinutes = 60).expiresWithin(5.minutes))
    }

    @Test
    fun `claimString reads nested auth claims and tolerates their absence`() {
        assertEquals("acct-9", jwt(expiresInMinutes = 5, accountId = "acct-9").claimString("chatgpt_account_id"))
        assertNull(jwt(expiresInMinutes = 5).claimString("chatgpt_account_id"))
    }

    @Test
    fun `CODEX_HOME resolves the auth file without reading the environment directly`() {
        assertEquals(Path.of("/srv/vusan-codex", "auth.json"), defaultCodexAuthFile(" /srv/vusan-codex "))
    }
}

private fun authFile(
    accessToken: String,
    idToken: String = accessToken,
    refreshToken: String? = "refresh-token",
    accountId: String? = "acct-1"
): Path {
    val file = Files.createTempDirectory("codex").resolve("auth.json")
    file.writeText(authJson(accessToken, idToken, refreshToken, accountId))

    return file
}

private fun authJson(
    accessToken: String,
    idToken: String = accessToken,
    refreshToken: String? = "refresh-token",
    accountId: String? = "acct-1"
): String {
    val refresh = refreshToken?.let { ""","refresh_token":"$it"""" }.orEmpty()
    val account = accountId?.let { ""","account_id":"$it"""" }.orEmpty()

    return """{"tokens":{"id_token":"$idToken","access_token":"$accessToken"$refresh$account}}"""
}

// a JWT only has to survive our own payload decoding, so the header and signature are placeholders.
private fun jwt(expiresInMinutes: Long, accountId: String? = null): String {
    val exp = Instant.now().plusSeconds(expiresInMinutes * 60).epochSecond
    val auth = accountId?.let { ""","https://api.openai.com/auth":{"chatgpt_account_id":"$it"}""" }.orEmpty()
    val payload = """{"exp":$exp$auth}"""

    return "header.${Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())}.signature"
}
