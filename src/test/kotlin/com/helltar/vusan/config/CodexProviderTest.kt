package com.helltar.vusan.config

import com.helltar.vusan.infra.Http
import com.helltar.vusan.llm.ReasoningEffort
import com.helltar.vusan.llm.codex.CodexAuthException
import com.helltar.vusan.llm.codex.CodexAuthStore
import com.helltar.vusan.llm.codex.CodexModel
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexProviderTest {

    @Test
    fun `catalog metadata configures the verbosity`() {
        val config = LlmProviderConfig.Codex(model = "text-model", requestTimeout = 120.seconds)

        assertEquals("low", applyCodexModelMetadata(config, codexModel(defaultVerbosity = "low")).verbosity)
        assertNull(applyCodexModelMetadata(config, codexModel()).verbosity)
    }

    @Test
    fun `catalog metadata configures context and vision`() {
        val config =
            LlmProviderConfig.Codex(
                model = "text-model",
                requestTimeout = 120.seconds,
            )
        val model = codexModel(supportsVision = false, contextWindowTokens = 128_000)

        val configured = applyCodexModelMetadata(config, model)

        assertEquals(128_000L, configured.contextWindowTokens)
        assertEquals(false, configured.seesImages)
    }

    @Test
    fun `an explicit context window wins over catalog metadata`() {
        val config =
            LlmProviderConfig.Codex(
                model = "text-model",
                requestTimeout = 120.seconds,
                contextWindowTokens = 32_000,
            )

        assertEquals(
            32_000L,
            applyCodexModelMetadata(config, codexModel(contextWindowTokens = 128_000)).contextWindowTokens,
        )
    }

    @Test
    fun `an unsupported reasoning effort fails before startup`() {
        val config =
            LlmProviderConfig.Codex(
                model = "text-model",
                reasoningEffort = ReasoningEffort.HIGH,
                requestTimeout = 120.seconds,
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                applyCodexModelMetadata(config, codexModel(supportedEfforts = setOf(ReasoningEffort.LOW)))
            }

        assertTrue("high" in error.message.orEmpty(), error.message.orEmpty())
        assertTrue("low" in error.message.orEmpty(), error.message.orEmpty())
    }

    // the backend takes none on models whose catalog lists low as the lowest
    @Test
    fun `none passes a catalog that names only thinking levels`() {
        val config = LlmProviderConfig.Codex(model = "text-model", reasoningEffort = ReasoningEffort.NONE, requestTimeout = 120.seconds)

        assertEquals(ReasoningEffort.NONE, applyCodexModelMetadata(config, codexModel(supportedEfforts = setOf(ReasoningEffort.LOW))).reasoningEffort)
    }

    @Test
    fun `a service tier the model does not offer fails before startup`() {
        val config =
            LlmProviderConfig.Codex(
                model = "text-model",
                serviceTier = ServiceTier.PRIORITY,
                requestTimeout = 120.seconds,
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                applyCodexModelMetadata(config, codexModel(supportedServiceTiers = emptySet()))
            }

        assertTrue("priority" in error.message.orEmpty(), error.message.orEmpty())
        assertTrue("text-model" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `a service tier the model offers passes the catalog check`() {
        val config =
            LlmProviderConfig.Codex(
                model = "text-model",
                serviceTier = ServiceTier.PRIORITY,
                requestTimeout = 120.seconds,
            )

        val configured = applyCodexModelMetadata(config, codexModel(supportedServiceTiers = setOf("priority")))

        assertEquals(ServiceTier.PRIORITY, configured.serviceTier)
    }

    @Test
    fun `an unknown tier list leaves the configured tier alone`() {
        val config =
            LlmProviderConfig.Codex(
                model = "text-model",
                serviceTier = ServiceTier.PRIORITY,
                requestTimeout = 120.seconds,
            )

        assertEquals(ServiceTier.PRIORITY, applyCodexModelMetadata(config, codexModel()).serviceTier)
    }

    @Test
    fun `verifyCodexModel accepts a model the subscription offers`() = runBlocking {
        val http = catalogClient("""{"models":[{"slug":"gpt-5.6-terra","display_name":"Terra","context_window":400000}]}""")

        val model = verifyCodexModel(http, store(), codex("GPT-5.6-Terra"))

        assertEquals("gpt-5.6-terra", model?.id)
        assertEquals(400_000L, model?.contextWindowTokens)
    }

    @Test
    fun `verifyCodexModel rejects a platform-only model and lists what is available`() = runBlocking {
        val http = catalogClient("""{"models":[{"slug":"gpt-5.6-terra","display_name":"Terra"}]}""")

        val error = assertFailsWith<IllegalStateException> { verifyCodexModel(http, store(), codex("gpt-4.1", envPrefix = "VISION")) }

        assertTrue("VISION_MODEL=[gpt-4.1]" in error.message.orEmpty(), error.message.orEmpty())
        assertTrue("gpt-5.6-terra" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `verifyCodexModel skips the check when the catalog cannot be read`() = runBlocking {
        val http = Http.createClient(MockEngine { respondJson("""{"detail":"nope"}""", HttpStatusCode.NotFound) })

        assertNull(verifyCodexModel(http, store(), codex("gpt-5.6-terra")))
    }

    @Test
    fun `verifyCodexModel skips the check when the catalog is empty`() = runBlocking {
        assertNull(verifyCodexModel(catalogClient("""{"models":[]}"""), store(), codex("gpt-5.6-terra")))
    }

    @Test
    fun `verifyCodexModel still fails when nobody is signed in`() = runBlocking {
        val store =
            CodexAuthStore(
                Http.createClient(MockEngine { respondJson("{}") }),
                Files.createTempDirectory("codex").resolve("auth.json"),
            )

        val error =
            assertFailsWith<CodexAuthException> { verifyCodexModel(catalogClient("""{"models":[]}"""), store, codex("any")) }

        assertTrue("codex login" in error.message.orEmpty(), error.message.orEmpty())
    }

    // a role runs a model of its own on the same plan: what the catalog said of the chat model is not its
    @Test
    fun `a role on the codex chat provider keeps neither the tier nor what the catalog said of the chat model`() {
        val chat =
            applyCodexModelMetadata(
                LlmProviderConfig.Codex(model = "gpt-5.6-terra", serviceTier = ServiceTier.PRIORITY, requestTimeout = 120.seconds),
                codexModel(supportsVision = false, supportedEfforts = setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), supportedServiceTiers = setOf("priority")),
            )

        assertEquals(setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), chat.efforts)

        val role = chat.withModel("gpt-5.6-luna", reasoningEffort = null, envPrefix = "ADDRESSING")

        assertNull(role.serviceTier)
        assertNull(role.efforts)
        assertNull(role.seesImages, "the role's own catalog entry says whether it sees, not the chat model's")
    }
}

private fun codexModel(
    supportsVision: Boolean = true,
    contextWindowTokens: Long? = null,
    supportedEfforts: Set<ReasoningEffort>? = null,
    supportedServiceTiers: Set<String>? = null,
    defaultVerbosity: String? = null,
): CodexModel =
    CodexModel(
        id = "text-model",
        displayName = "Text Model",
        contextWindowTokens = contextWindowTokens,
        supportsVision = supportsVision,
        supportedReasoningEfforts = supportedEfforts,
        supportedServiceTiers = supportedServiceTiers,
        defaultVerbosity = defaultVerbosity,
    )

private fun codex(model: String, envPrefix: String = "LLM") = LlmProviderConfig.Codex(model = model, requestTimeout = 120.seconds, envPrefix = envPrefix)

private fun catalogClient(body: String) = Http.createClient(MockEngine { respondJson(body) })

private fun MockRequestHandleScope.respondJson(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respond(
        content = ByteReadChannel(body),
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

private fun store(): CodexAuthStore =
    CodexAuthStore(Http.createClient(MockEngine { error("no refresh expected") }), signedInAuthFile())

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
