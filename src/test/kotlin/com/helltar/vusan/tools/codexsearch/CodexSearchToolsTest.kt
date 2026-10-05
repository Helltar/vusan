package com.helltar.vusan.tools.codexsearch

import com.helltar.vusan.config.CodexAuthStore
import com.helltar.vusan.infra.Http
import com.helltar.vusan.tools.toolFailure
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.*
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodexSearchToolsTest {

    @Test
    fun `the request forces the hosted search on the chatgpt session`() = runBlocking {
        val http =
            Http.createClient(
                MockEngine { request ->
                    assertEquals("chatgpt.com", request.url.host)
                    assertEquals("/backend-api/codex/responses", request.url.encodedPath)

                    // the same cloudflare-whitelisted set the chat traffic uses; a miss here only
                    // fails once deployed to a non-residential ip.
                    assertEquals("codex_cli_rs", request.headers["originator"])
                    assertTrue(request.headers[HttpHeaders.UserAgent].orEmpty().startsWith("codex_cli_rs/"))
                    assertEquals("acct-1", request.headers["ChatGPT-Account-ID"])
                    assertTrue(request.headers[HttpHeaders.Authorization].orEmpty().startsWith("Bearer "))

                    val payload = Json.parseToJsonElement(assertIs<TextContent>(request.body).text).jsonObject
                    assertEquals("model-one", payload["model"]?.jsonPrimitive?.content)

                    // the backend refuses anything but a streamed, unstored request
                    assertEquals("true", payload["stream"]?.jsonPrimitive?.content)
                    assertEquals("false", payload["store"]?.jsonPrimitive?.content)

                    // the hosted tool alone and forced: beside function tools the model skips it
                    val tools = payload["tools"].let { checkNotNull(it) }.jsonArray
                    assertEquals(1, tools.size)
                    assertEquals("web_search", tools[0].jsonObject["type"]?.jsonPrimitive?.content)
                    assertEquals("web_search", payload["tool_choice"]?.jsonObject?.get("type")?.jsonPrimitive?.content)

                    val content = payload["input"].let { checkNotNull(it) }.jsonArray[0].jsonObject["content"]
                    assertEquals(
                        "how tall is the lighthouse",
                        content.let { checkNotNull(it) }.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content,
                    )

                    respondStream(SEARCH_CALL, message("It is 40 metres tall."))
                },
            )

        val result = tools(http).answerFromWeb("  how tall is the lighthouse ")

        assertContains(result, "<web_answer>\nIt is 40 metres tall.\n</web_answer>")
    }

    @Test
    fun `sources are listed once each without the tracking marker`() = runBlocking {
        val text = "Built in 1901 ([example.org](https://example.org/tower?utm_source=openai))."

        // one line: an event is one `data:` line of the stream
        val annotations =
            listOf(
                """{"type":"url_citation","title":"The  Tower","url":"https://example.org/tower?utm_source=openai"}""",
                """{"type":"url_citation","title":"The Tower","url":"https://example.org/tower?utm_source=openai"}""",
                """{"type":"url_citation","title":"","url":"https://example.com/a?utm_source=openai&page=2"}""",
            ).joinToString(",")

        val http = Http.createClient(MockEngine { respondStream(SEARCH_CALL, message(text, annotations)) })

        val result = tools(http).answerFromWeb("when was the tower built")

        assertContains(result, "Built in 1901 ([example.org](https://example.org/tower)).")
        assertContains(result, "Sources:\n1. The Tower — https://example.org/tower\n2. https://example.com/a?page=2")
        assertFalse(result.contains("utm_source"))
    }

    @Test
    fun `an answer written without a search is a failure`() = runBlocking {
        val http = Http.createClient(MockEngine { respondStream(message("Probably 40 metres.")) })

        val failure = toolFailure { tools(http).answerFromWeb("how tall is the lighthouse") }

        assertContains(failure, "without searching")
    }

    @Test
    fun `a failed stream is a failure`() = runBlocking {
        val http =
            Http.createClient(
                MockEngine {
                    respond(
                        content = """data: {"type":"response.failed","response":{"error":{"message":"overloaded"}}}""" + "\n\n",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                    )
                },
            )

        val failure = toolFailure { tools(http).answerFromWeb("how tall is the lighthouse") }

        assertContains(failure, "overloaded")
    }

    @Test
    fun `a blank question never reaches the backend`() = runBlocking {
        val http = Http.createClient(MockEngine { error("no request expected") })

        val failure = toolFailure { tools(http).answerFromWeb("   ") }

        assertContains(failure, "Question must not be empty")
    }
}

private const val SEARCH_CALL =
    """{"type":"web_search_call","status":"completed","action":{"type":"search","query":"lighthouse height"}}"""

private fun message(text: String, annotations: String = ""): String {
    val quoted = Json.encodeToString(text)

    return """{"type":"message","role":"assistant","content":[{"type":"output_text","text":$quoted,"annotations":[$annotations]}]}"""
}

// the backend leaves the final event's `output` empty and sends the items one event each
private fun MockRequestHandleScope.respondStream(vararg items: String) =
    respond(
        content =
            items.joinToString("") { """data: {"type":"response.output_item.done","item":$it}""" + "\n\n" } +
                    """data: {"type":"response.completed","response":{"status":"completed","output":[],""" +
                    """"usage":{"input_tokens":10,"output_tokens":5}}}""" + "\n\n",
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
    )

private fun tools(http: io.ktor.client.HttpClient): CodexSearchTools =
    CodexSearchTools(
        CodexSearchClient(
            http,
            CodexAuthStore(Http.createClient(MockEngine { error("no refresh expected") }), signedInAuthFile()),
            "model-one",
        ),
    )

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
