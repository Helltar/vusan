package com.helltar.vusan.llm.codex

import com.helltar.vusan.infra.Http
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.*
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexRequestPluginTest {

    @Test
    fun `a conversation's requests carry one session id, a UUID`() = runBlocking {
        val first = sentHeaders("""{"model":"m","prompt_cache_key":"vusan-1a2b"}""")
        val second = sentHeaders("""{"model":"m","prompt_cache_key":"vusan-1a2b","input":[{"role":"user"}]}""")

        val sessionId = checkNotNull(first["session-id"])

        assertEquals(sessionId, UUID.fromString(sessionId).toString())
        assertEquals(sessionId, second["session-id"])
    }

    @Test
    fun `another conversation gets another session id`() = runBlocking {
        val one = sentHeaders("""{"prompt_cache_key":"vusan-1a2b"}""")
        val other = sentHeaders("""{"prompt_cache_key":"vusan-3c4d"}""")

        assertNotEquals(one["session-id"], other["session-id"])
    }

    @Test
    fun `a request without a cache key names no session`() = runBlocking {
        assertNull(sentHeaders("""{"model":"m"}""")["session-id"])
        assertNull(sentHeaders("not json")["session-id"])
    }

    @Test
    fun `every request carries the token, the account and the whitelisted originator`() = runBlocking {
        val headers = sentHeaders("""{"model":"m"}""")

        assertTrue(headers["Authorization"].orEmpty().startsWith("Bearer header."))
        assertEquals("acct-1", headers["ChatGPT-Account-ID"])
        assertEquals(CODEX_ORIGINATOR, headers["originator"])
        assertEquals("model=m", headers["x-codex-routing-hint"])
        assertTrue(headers["User-Agent"].orEmpty().startsWith("$CODEX_ORIGINATOR/"))
    }

    // through a ktor client the way a model call goes, with the body as a string, which is what the
    // plugin reads the cache key out of.
    private suspend fun sentHeaders(body: String): Headers {
        var sent: Headers? = null

        val engine =
            MockEngine { request ->
                sent = request.headers
                respond("{}", headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }

        val auth = CodexAuthStore(Http.createClient(MockEngine { error("no refresh expected") }), signedInAuthFile())

        HttpClient(engine) { install(codexRequestPlugin(auth, "model=m")) }.use { client ->
            client.post("https://codex.example.test/responses") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

        return checkNotNull(sent)
    }
}

private fun signedInAuthFile(): Path {
    val exp = Instant.now().plusSeconds(3600).epochSecond
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":$exp}""".toByteArray())
    val token = "header.$payload.signature"

    val file = Files.createTempDirectory("codex").resolve("auth.json")
    file.writeText(
        """{"tokens":{"id_token":"$token","access_token":"$token","refresh_token":"r","account_id":"acct-1"}}""",
    )

    return file
}
