package com.helltar.vusan.llm.codex

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
}

/** Zero-padded so plain string ordering matches version ordering, independently of production code. */
private fun comparable(version: String): String = version.split('.').joinToString(".") { it.padStart(5, '0') }

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
