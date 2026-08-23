package com.helltar.vusan.config

import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import com.helltar.vusan.infra.Http
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexCatalogTest {

    @Test
    fun `the catalog maps slugs and context windows`() = runBlocking {
        val http = catalogClient(
            """
            {"models":[
              {"slug":"gpt-5.6-terra","display_name":"GPT-5.6 Terra","context_window":400000,
               "input_modalities":["text","image"],"supported_reasoning_efforts":["low","medium","high"]},
              {"slug":"gpt-5.6-mini","display_name":"GPT-5.6 Mini","max_context_window":272000,
               "input_modalities":["text"],"supported_reasoning_levels":[{"effort":"low"},{"level":"high"}]}
            ]}
            """.trimIndent()
        )

        val models = fetchCodexModels(http, store())

        assertEquals(listOf("gpt-5.6-terra", "gpt-5.6-mini"), models.map { it.id })
        assertEquals(400_000L, models.first().contextWindowTokens)
        // max_context_window stands in when the preferred field is absent
        assertEquals(272_000L, models.last().contextWindowTokens)
        assertTrue(models.first().supportsVision)
        assertEquals(
            setOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            models.first().supportedReasoningEfforts
        )
        assertTrue(!models.last().supportsVision)
        assertEquals(setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), models.last().supportedReasoningEfforts)
    }

    @Test
    fun `missing capability metadata keeps compatibility defaults`() = runBlocking {
        val models = fetchCodexModels(catalogClient("""{"models":[{"slug":"legacy"}]}"""), store())

        assertTrue(models.single().supportsVision)
        assertNull(models.single().supportedReasoningEfforts)
    }

    @Test
    fun `catalog metadata configures context and vision`() {
        val config =
            LlmProviderConfig.Codex(
                model = "text-model",
                requestTimeout = 120.seconds
            )
        val model = codexModel(supportsVision = false, contextWindowTokens = 128_000)

        val configured = applyCodexModelMetadata(config, model)

        assertEquals(128_000L, configured.contextWindowTokens)
        assertTrue(!configured.supportsVision)
    }

    @Test
    fun `an explicit context window wins over catalog metadata`() {
        val config =
            LlmProviderConfig.Codex(
                model = "text-model",
                requestTimeout = 120.seconds,
                contextWindowTokens = 32_000
            )

        assertEquals(
            32_000L,
            applyCodexModelMetadata(config, codexModel(contextWindowTokens = 128_000)).contextWindowTokens
        )
    }

    @Test
    fun `an unsupported reasoning effort fails before startup`() {
        val config =
            LlmProviderConfig.Codex(
                model = "text-model",
                reasoningEffort = ReasoningEffort.HIGH,
                requestTimeout = 120.seconds
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                applyCodexModelMetadata(config, codexModel(supportedEfforts = setOf(ReasoningEffort.LOW)))
            }

        assertTrue("high" in error.message.orEmpty(), error.message.orEmpty())
        assertTrue("low" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `codex version detection times out before reading process output`() {
        var version: String? = null
        val elapsed =
            measureTime {
                version = detectCodexClientVersion(listOf("/bin/sleep", "30"), 100.milliseconds)
            }

        assertNull(version)
        assertTrue(elapsed < 2.seconds, "elapsed=[$elapsed]")
    }

    @Test
    fun `the request carries the bearer token and account header`() = runBlocking {
        var authorization: String? = null
        var account: String? = null

        val http =
            Http.createClient(
                MockEngine { request ->
                    authorization = request.headers[HttpHeaders.Authorization]
                    account = request.headers["ChatGPT-Account-ID"]
                    assertEquals("chatgpt.com", request.url.host)
                    assertEquals("/backend-api/codex/models", request.url.encodedPath)
                    // the endpoint 400s without it
                    assertNotNull(request.url.parameters["client_version"])

                    respondJson("""{"models":[]}""")
                }
            )

        fetchCodexModels(http, store())

        assertTrue(authorization.orEmpty().startsWith("Bearer "), authorization.orEmpty())
        assertEquals("acct-1", account)
    }

    @Test
    fun `the request advertises a Cloudflare-whitelisted originator and user agent`() = runBlocking {
        var originator: String? = null
        var userAgent: String? = null

        val http =
            Http.createClient(
                MockEngine { request ->
                    originator = request.headers["originator"]
                    userAgent = request.headers[HttpHeaders.UserAgent]
                    respondJson("""{"models":[]}""")
                }
            )

        fetchCodexModels(http, store())

        // Cloudflare answers a non-whitelisted originator with 403 from any non-residential IP, so this
        // has to stay one of the first-party values however tempting a truthful name is.
        assertEquals("codex_cli_rs", originator)
        assertTrue(userAgent.orEmpty().startsWith("codex_cli_rs/"), userAgent.orEmpty())
        assertTrue(userAgent.orEmpty().endsWith("(Vusan)"), userAgent.orEmpty())
    }

    @Test
    fun `verifyCodexModel accepts a model the subscription offers`() = runBlocking {
        val http = catalogClient("""{"models":[{"slug":"gpt-5.6-terra","display_name":"Terra","context_window":400000}]}""")

        val model = verifyCodexModel(http, store(), "GPT-5.6-Terra")

        assertEquals("gpt-5.6-terra", model?.id)
        assertEquals(400_000L, model?.contextWindowTokens)
    }

    @Test
    fun `verifyCodexModel rejects a platform-only model and lists what is available`() = runBlocking {
        val http = catalogClient("""{"models":[{"slug":"gpt-5.6-terra","display_name":"Terra"}]}""")

        val error = assertFailsWith<IllegalStateException> { verifyCodexModel(http, store(), "gpt-4.1") }

        assertTrue("gpt-4.1" in error.message.orEmpty(), error.message.orEmpty())
        assertTrue("gpt-5.6-terra" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `verifyCodexModel skips the check when the catalog cannot be read`() = runBlocking {
        val http = Http.createClient(MockEngine { respondJson("""{"detail":"nope"}""", HttpStatusCode.NotFound) })

        assertNull(verifyCodexModel(http, store(), "gpt-5.6-terra"))
    }

    @Test
    fun `verifyCodexModel skips the check when the catalog is empty`() = runBlocking {
        assertNull(verifyCodexModel(catalogClient("""{"models":[]}"""), store(), "gpt-5.6-terra"))
    }

    @Test
    fun `verifyCodexModel still fails when nobody is signed in`() = runBlocking {
        val store =
            CodexAuthStore(
                Http.createClient(MockEngine { respondJson("{}") }),
                Files.createTempDirectory("codex").resolve("auth.json")
            )

        val error =
            assertFailsWith<CodexAuthException> { verifyCodexModel(catalogClient("""{"models":[]}"""), store, "any") }

        assertTrue("codex login" in error.message.orEmpty(), error.message.orEmpty())
    }
}

private fun codexModel(
    supportsVision: Boolean = true,
    contextWindowTokens: Long? = null,
    supportedEfforts: Set<ReasoningEffort>? = null
): CodexModel =
    CodexModel(
        id = "text-model",
        displayName = "Text Model",
        contextWindowTokens = contextWindowTokens,
        supportsVision = supportsVision,
        supportedReasoningEfforts = supportedEfforts
    )

private fun catalogClient(body: String) = Http.createClient(MockEngine { respondJson(body) })

private fun MockRequestHandleScope.respondJson(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respond(
        content = ByteReadChannel(body),
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    )

private fun store(): CodexAuthStore =
    CodexAuthStore(Http.createClient(MockEngine { error("no refresh expected") }), signedInAuthFile())

private fun signedInAuthFile(): Path {
    val exp = Instant.now().plusSeconds(3600).epochSecond
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":$exp}""".toByteArray())
    val token = "header.$payload.signature"

    val file = Files.createTempDirectory("codex").resolve("auth.json")
    file.writeText(
        """{"tokens":{"id_token":"$token","access_token":"$token","refresh_token":"r","account_id":"acct-1"}}"""
    )

    return file
}
