package com.helltar.vusan.stt

import com.helltar.vusan.config.OpenAiSttConfig
import com.helltar.vusan.infra.Http
import io.ktor.client.engine.mock.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenAiWhisperClientTest {

    private val config = OpenAiSttConfig(
        apiKey = "sk-test",
        model = "whisper-1",
        maxDurationSeconds = 600
    )

    @Test
    fun `transcribe posts multipart form and returns recognized text`() = runBlocking {
        val http =
            Http.createClient(
                MockEngine { request ->
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("api.openai.com", request.url.host)
                    assertEquals("/v1/audio/transcriptions", request.url.encodedPath)
                    assertEquals("Bearer sk-test", request.headers[HttpHeaders.Authorization])

                    val body = request.body
                    assertTrue(body is MultiPartFormDataContent, "expected multipart body, got ${body::class.simpleName}")

                    respond(
                        content = ByteReadChannel("""{"text":"hello world"}"""),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            )
        val client = OpenAiWhisperClient(http, config)

        val text = client.transcribe(byteArrayOf(1, 2, 3), "voice.oga", "audio/ogg")

        assertEquals("hello world", text)
        http.close()
    }

    @Test
    fun `transcribe surfaces provider errors as HTTP exception`() = runBlocking {
        val http =
            Http.createClient(
                MockEngine {
                    respond(
                        content = """{"error":{"message":"invalid file"}}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            )
        val client = OpenAiWhisperClient(http, config)

        val error = assertFailsWith<IllegalStateException> {
            client.transcribe(byteArrayOf(1, 2, 3), "voice.oga", "audio/ogg")
        }

        val message = assertNotNull(error.message)
        assertTrue(message.contains("api.openai.com"))
        assertTrue(message.contains("400"))
        http.close()
    }

    @Test
    fun `transcribe rejects empty audio`() {
        val client = OpenAiWhisperClient(http = io.ktor.client.HttpClient(MockEngine { error("unused") }), config = config)

        assertFailsWith<IllegalArgumentException> {
            runBlocking { client.transcribe(ByteArray(0), "voice.oga", "audio/ogg") }
        }
    }
}
