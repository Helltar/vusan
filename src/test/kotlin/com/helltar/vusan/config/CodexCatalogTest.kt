package com.helltar.vusan.config

import com.helltar.vusan.infra.Http
import com.helltar.vusan.llm.ReasoningEffort
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
import kotlin.test.assertFalse
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
            models.first().supportedReasoningEfforts,
        )
        assertTrue(!models.last().supportsVision)
        assertEquals(setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), models.last().supportedReasoningEfforts)
    }

    @Test
    fun `missing capability metadata keeps compatibility defaults`() = runBlocking {
        val models = fetchCodexModels(catalogClient("""{"models":[{"slug":"legacy"}]}"""), store())

        assertTrue(models.single().supportsVision)
        assertNull(models.single().supportedReasoningEfforts)
        assertNull(models.single().supportedServiceTiers)
    }

    // the CLI never sends `ultra` as spelled: it swaps in another effort before building the request, so
    // a catalog listing it does not make it a value the backend is known to take.
    @Test
    fun `the catalog keeps efforts above high but not the ultra alias`() = runBlocking {
        val models = fetchCodexModels(
            catalogClient(
                """
                {"models":[{"slug":"gpt-5.6-sol","supported_reasoning_levels":[
                  {"effort":"low"},{"effort":"high"},{"effort":"xhigh"},{"effort":"max"},{"effort":"ultra"}
                ]}]}
                """.trimIndent()
            ),
            store(),
        )

        assertEquals(
            setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX),
            models.single().supportedReasoningEfforts,
        )
    }

    @Test
    fun `the catalog reports which serving tiers a model offers`() = runBlocking {
        val models = fetchCodexModels(
            catalogClient(
                """
                {"models":[
                  {"slug":"fast-model","service_tiers":[{"id":"priority","name":"Fast",
                   "description":"1.5x speed, increased usage"}]},
                  {"slug":"plain-model","service_tiers":[]}
                ]}
                """.trimIndent()
            ),
            store(),
        )

        assertEquals(setOf("priority"), models.first().supportedServiceTiers)
        // an explicit empty array is the catalog saying this model has no tier beyond the standard one
        assertEquals(emptySet(), models.last().supportedServiceTiers)
    }

    @Test
    fun `the catalog's default verbosity counts only for a model that takes one`() = runBlocking {
        val models = fetchCodexModels(
            catalogClient(
                """
                {"models":[
                  {"slug":"terse-model","support_verbosity":true,"default_verbosity":"low"},
                  {"slug":"deaf-model","support_verbosity":false,"default_verbosity":"low"},
                  {"slug":"plain-model"}
                ]}
                """.trimIndent()
            ),
            store(),
        )

        assertEquals(listOf("low", null, null), models.map { it.defaultVerbosity })
    }

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
    fun `version ordering compares numbers rather than strings`() {
        // the pair that matters: as strings "0.99.0" sorts above "0.146.1"
        assertTrue("0.146.1" isNewerThan "0.99.0")
        assertTrue("0.153.4" isNewerThan "0.153.0")
        assertTrue("0.153.1" isNewerThan "0.153")
        assertTrue(!("0.153.0" isNewerThan "0.153.0"))
        assertTrue(!("0.152.9" isNewerThan "0.153.0"))
    }

    @Test
    fun `the catalog request claims a client version the newest models require`() = runBlocking {
        var version: String? = null

        val http =
            Http.createClient(
                MockEngine { request ->
                    version = request.url.parameters["client_version"]
                    respondJson("""{"models":[]}""")
                },
            )

        fetchCodexModels(http, store())

        // `/models` omits every model whose `minimal_client_version` is newer than what we claim, and a
        // missing model reads as one the plan does not offer. `0.153.0` is what gpt-6-astra requires.
        assertTrue(comparable(version.orEmpty()) >= comparable("0.153.0"), version.orEmpty())
    }

    @Test
    fun `a pinned client version is what the catalog request claims, and nobody else is asked`() = runBlocking {
        var version: String? = null
        var gitHubAsked = false
        var cliAsked = false

        val http =
            Http.createClient(
                MockEngine { request ->
                    version = request.url.parameters["client_version"]
                    respondJson("""{"models":[]}""")
                },
            )

        try {
            claimCodexClientVersion(Http.createClient(MockEngine { gitHubAsked = true; respondJson("{}") }), "0.199.0") {
                cliAsked = true
                null
            }
            fetchCodexModels(http, store())
        } finally {
            claimFloor()
        }

        assertEquals("0.199.0", version)
        assertFalse(gitHubAsked)
        assertFalse(cliAsked)
    }

    @Test
    fun `the latest GitHub release is read from its tag`() = runBlocking {
        var url: String? = null

        val http =
            Http.createClient(
                MockEngine { request ->
                    url = request.url.toString()
                    respondJson("""{"tag_name":"rust-v0.162.0","name":"0.162.0","prerelease":false}""")
                },
            )

        assertEquals("0.162.0", fetchLatestCodexRelease(http))
        assertEquals("https://api.github.com/repos/openai/codex/releases/latest", url)
    }

    @Test
    fun `a GitHub release that cannot be read is skipped`() = runBlocking {
        val rateLimited = Http.createClient(MockEngine { respondJson("""{"message":"rate limited"}""", HttpStatusCode.Forbidden) })
        val unversioned = Http.createClient(MockEngine { respondJson("""{"tag_name":"nightly"}""") })

        assertNull(fetchLatestCodexRelease(rateLimited))
        assertNull(fetchLatestCodexRelease(unversioned))
    }

    @Test
    fun `startup claims the newest version any source offers`() = runBlocking {
        try {
            claimCodexClientVersion(gitHub("0.199.0"), null) { "0.170.0" }
            assertEquals("0.199.0", codexClientVersion())

            claimCodexClientVersion(gitHub("0.170.0"), null) { "0.199.1" }
            assertEquals("0.199.1", codexClientVersion())

            // neither source beats the floor this build ships, so the floor is what is claimed
            claimCodexClientVersion(gitHub("0.100.0"), null) { "0.99.0" }
            assertTrue(codexClientVersion() isNewerThan "0.100.0", codexClientVersion())
        } finally {
            claimFloor()
        }
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
                },
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
                },
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

        val role = chat.withModel("gpt-5.6-luna", reasoningEffort = null, contextWindowTokens = null, envPrefix = "ADDRESSING")

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

/** Zero-padded so plain string ordering matches version ordering, independently of production code. */
private fun comparable(version: String): String = version.split('.').joinToString(".") { it.padStart(5, '0') }

private fun codex(model: String, envPrefix: String = "LLM") = LlmProviderConfig.Codex(model = model, requestTimeout = 120.seconds, envPrefix = envPrefix)

private fun catalogClient(body: String) = Http.createClient(MockEngine { respondJson(body) })

private fun gitHub(version: String) = Http.createClient(MockEngine { respondJson("""{"tag_name":"rust-v$version"}""") })

/** Back to what an unclaimed process reports, so the tests after this one read the floor again. */
private suspend fun claimFloor() =
    claimCodexClientVersion(Http.createClient(MockEngine { respondJson("{}", HttpStatusCode.NotFound) }), null) { null }

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
