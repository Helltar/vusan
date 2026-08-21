package com.helltar.vusan.tools.tgchannel

import com.helltar.vusan.infra.Http
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

private val NOW = Instant.parse("2026-03-04T12:00:00Z")

class TelegramChannelReaderTest {

    private val requested = mutableListOf<Url>()

    private fun reader(respond: MockRequestHandleScope.(Url) -> HttpResponseData) =
        TelegramChannelReader(
            client = TelegramChannelClient(
                Http.createClient(
                    MockEngine { request ->
                        requested += request.url
                        respond(request.url)
                    }
                )
            ),
            imageDescriber = null,
            zone = ZoneId.of("UTC")
        )

    private fun TelegramChannelReader.readChannel(
        window: Duration? = null,
        query: String = "",
        maxPosts: Int = 0
    ): String =
        runBlocking {
            read(
                channel = "example_channel",
                window = window,
                query = query,
                maxPosts = maxPosts,
                describeImages = false,
                imageFocus = "",
                now = NOW
            )
        }

    private fun MockRequestHandleScope.html(body: String) =
        respond(body, headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"))

    @Test
    fun `a window walks pages back until one reaches past the cutoff`() {
        val reader =
            reader { url ->
                when (url.parameters["before"]) {
                    null ->
                        html(
                            channelPage(
                                posts = listOf(
                                    channelPost(id = 40, text = "Second newest", at = "2026-03-04T10:00:00+00:00"),
                                    channelPost(id = 41, text = "Newest of all", at = "2026-03-04T11:00:00+00:00")
                                ),
                                moreBefore = 40
                            )
                        )

                    "40" ->
                        html(
                            channelPage(
                                posts = listOf(
                                    channelPost(id = 38, text = "Older than the window", at = "2026-03-02T20:00:00+00:00"),
                                    channelPost(id = 39, text = "Still inside the window", at = "2026-03-03T20:00:00+00:00")
                                ),
                                moreBefore = 38
                            )
                        )

                    else -> error("walked past the cutoff to before=${url.parameters["before"]}")
                }
            }

        val result = reader.readChannel(window = 24.hours)

        assertEquals(2, requested.size)
        assertContains(result, "Posts in it: 3")
        assertContains(result, "Still inside the window")
        assertFalse("Older than the window" in result)
        // newest first across the page boundary
        assertTrue(result.indexOf("Newest of all") < result.indexOf("Still inside the window"))
    }

    @Test
    fun `without a window one full page already covers the default`() {
        val reader =
            reader {
                html(
                    channelPage(
                        posts = (1..20).map { channelPost(id = it, text = "Post $it") },
                        moreBefore = 1
                    )
                )
            }

        val result = reader.readChannel()

        assertEquals(1, requested.size)
        assertContains(result, "Posts read: 12 recent post(s)")
    }

    @Test
    fun `a channel shorter than the request stops where telegram stops offering more`() {
        val reader =
            reader {
                html(channelPage(posts = listOf(channelPost(id = 10, text = "The only post ever"))))
            }

        val result = reader.readChannel()

        assertEquals(1, requested.size)
        assertContains(result, "Posts read: 1 recent post(s)")
        assertContains(result, "The only post ever")
    }

    @Test
    fun `asking for more posts than a page holds keeps paging`() {
        val reader =
            reader { url ->
                val first = url.parameters["before"]?.toInt() ?: 61
                html(
                    channelPage(
                        posts = ((first - 20) until first).map { channelPost(id = it, text = "Post ${'$'}it") },
                        moreBefore = first - 20
                    )
                )
            }

        val result = reader.readChannel(maxPosts = 25)

        assertEquals(2, requested.size)
        assertContains(result, "Posts read: 25 recent post(s)")
    }

    @Test
    fun `a username without a public preview is reported as unreadable`() {
        val reader =
            reader { url ->
                if (url.encodedPath.startsWith("/s/")) {
                    respond(
                        "",
                        status = HttpStatusCode.Found,
                        headers = headersOf(HttpHeaders.Location, "https://t.me/example_channel")
                    )
                } else {
                    html("<html><body>Open in Telegram</body></html>")
                }
            }

        val result = reader.readChannel()

        assertContains(result, "no public web preview")
    }

    @Test
    fun `an empty window says when the channel last posted`() {
        val reader =
            reader {
                html(
                    channelPage(
                        posts = listOf(channelPost(id = 5, text = "Long ago", at = "2026-02-20T08:00:00+00:00"))
                    )
                )
            }

        val result = reader.readChannel(window = 24.hours)

        assertContains(result, "posted nothing between")
        assertContains(result, "newest post is from 2026-02-20T08:00")
    }

    @Test
    fun `a search is passed to telegram and named in the header`() {
        val reader =
            reader {
                html(channelPage(posts = listOf(channelPost(id = 9, text = "Roadmap update"))))
            }

        val result = reader.readChannel(query = "roadmap")

        assertEquals("roadmap", requested.single().parameters["q"])
        assertContains(result, "Search: posts matching `roadmap`")
    }

    @Test
    fun `a search with no hits says so instead of reporting an empty channel`() {
        val reader = reader { html(channelPage(posts = emptyList())) }

        assertContains(reader.readChannel(query = "nothing here"), "No posts matching `nothing here`")
    }

    @Test
    fun `a window the walk could not finish is flagged in the header`() {
        val reader =
            reader {
                html(
                    channelPage(
                        posts = listOf(
                            channelPost(id = 70, text = "Third", at = "2026-03-04T09:00:00+00:00"),
                            channelPost(id = 71, text = "Second", at = "2026-03-04T10:00:00+00:00"),
                            channelPost(id = 72, text = "First", at = "2026-03-04T11:00:00+00:00")
                        ),
                        moreBefore = 70
                    )
                )
            }

        val result = reader.readChannel(window = 24.hours, maxPosts = 2)

        assertContains(result, "posts faster than the window could be walked")
        assertContains(result, "covered back to 2026-03-04T10:00")
    }

    @Test
    fun `posts past the output limit are dropped and announced`() {
        // 2500 chars of text per post, so a couple of dozen posts already pass the tool's ceiling
        val body = "A sentence of filler that makes this post long enough to matter. ".repeat(40)

        val reader =
            reader { url ->
                val first = url.parameters["before"]?.toInt() ?: 61
                html(
                    channelPage(
                        posts = ((first - 20) until first).map {
                            channelPost(id = it, text = "$it $body", at = "2026-03-04T11:00:00+00:00")
                        },
                        moreBefore = first - 20
                    )
                )
            }

        val result = reader.readChannel(window = 24.hours, maxPosts = 40)

        assertContains(result, "Posts in it: 40")
        assertContains(result, "fit this tool's size limit")
        assertContains(result, "<channel_posts>")
        // every post that did make it is whole, not shortened to squeeze more in
        assertTrue(result.length < 60_000)
    }
}
