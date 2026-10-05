package com.helltar.vusan.config

import ai.koog.http.client.ktor.KtorKoogHttpClient
import com.helltar.vusan.infra.Http
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.*
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

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

    // goes through koog's own ktor client, the way a model call does, so the body reaches the plugin in
    // the shape it has in production.
    private suspend fun sentHeaders(body: String): Headers {
        var sent: Headers? = null

        val engine =
            MockEngine { request ->
                sent = request.headers
                respond("data: [DONE]\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }

        val auth = CodexAuthStore(Http.createClient(MockEngine { error("no refresh expected") }), signedInAuthFile())

        KtorKoogHttpClient.Factory(baseClient = HttpClient(engine) { install(codexRequestPlugin(auth, "model=m")) })
            .create(
                clientName = "codex",
                baseUrl = "https://codex.example.test",
                headers = emptyMap(),
                queryParameters = emptyMap(),
                requestTimeoutMillis = 5_000,
                connectTimeoutMillis = 5_000,
                socketTimeoutMillis = 5_000,
                json = Json,
            )
            .use { client ->
                client.lines(
                    path = "responses",
                    requestBody = body,
                    requestBodyType = String::class,
                    parameters = emptyMap(),
                    headers = emptyMap(),
                ).toList()
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
