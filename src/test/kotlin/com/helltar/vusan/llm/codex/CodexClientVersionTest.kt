package com.helltar.vusan.llm.codex

import com.helltar.vusan.infra.Http
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexClientVersionTest {

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
    fun `a pinned client version is claimed, and nobody else is asked`() = runBlocking {
        var gitHubAsked = false
        var cliAsked = false

        try {
            claimCodexClientVersion(Http.createClient(MockEngine { gitHubAsked = true; respondJson("{}") }), "0.199.0") {
                cliAsked = true
                null
            }

            assertEquals("0.199.0", codexClientVersion())
        } finally {
            claimFloor()
        }

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
}

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
