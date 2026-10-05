package com.helltar.vusan.tools.giphy

import com.helltar.vusan.infra.Http
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.ChatCapabilities
import com.helltar.vusan.tools.toolFailure
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GiphyToolsTest {

    @Test
    fun `a search lists candidates and queues nothing`() = runBlocking {
        val outbox = BotOutbox()
        val result = tools(outbox).searchGifs("sleepy cat")

        assertContains(result, "cat1: Sleepy Cat")
        assertContains(result, "owl2: Blinking Owl")
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun `a candidate without a usable url is not offered`() = runBlocking {
        val result = tools(BotOutbox()).searchGifs("sleepy cat")

        assertTrue("nourl3" !in result)
    }

    @Test
    fun `sending a candidate queues its animation`() = runBlocking {
        val outbox = BotOutbox()
        val tools = tools(outbox)

        tools.searchGifs("sleepy cat")
        tools.sendGif("owl2")

        val animation = assertIs<BotOutput.Animation>(outbox.pending.single().output)
        assertEquals("https://media.example.test/owl2.gif", animation.url)
    }

    @Test
    fun `the same candidate is not sent twice in a turn, whichever search returned it`() = runBlocking {
        val outbox = BotOutbox()
        val tools = tools(outbox)

        tools.searchGifs("sleepy cat")
        tools.sendGif("cat1")

        assertContains(tools.searchGifs("drowsy kitten"), "cat1: Sleepy Cat (already sent in this turn)")
        assertContains(toolFailure { tools.sendGif("cat1") }, "already sent")
        assertEquals(1, outbox.pending.size)
    }

    @Test
    fun `an id no search returned is rejected`() = runBlocking {
        val outbox = BotOutbox()
        val tools = tools(outbox)

        tools.searchGifs("sleepy cat")

        assertContains(toolFailure { tools.sendGif("https://media.example.test/other.mp4") }, "unknown GIF id")
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun `a chat that refuses GIFs is reported as a failure`() = runBlocking {
        val outbox = BotOutbox(ChatCapabilities(stickersAndAnimations = false))
        val tools = tools(outbox)

        tools.searchGifs("sleepy cat")

        assertContains(toolFailure { tools.sendGif("cat1") }, "nothing was sent")
        assertTrue(outbox.pending.isEmpty())
    }

    private fun tools(outbox: BotOutbox): GiphyTools {
        val http =
            Http.createClient(
                MockEngine {
                    respond(
                        content = SEARCH_RESPONSE,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                },
            )

        return GiphyTools(GiphyClient(http, "test-key"), outbox)
    }

    private companion object {
        const val SEARCH_RESPONSE = """
            {
              "data": [
                {"id": "cat1", "title": "Sleepy Cat", "images": {"original": {"mp4": "https://media.example.test/cat1.mp4"}}},
                {"id": "owl2", "title": "Blinking Owl", "images": {"original": {"url": "https://media.example.test/owl2.gif"}}},
                {"id": "nourl3", "title": "Lost Fox", "images": {"original": {}}}
              ],
              "meta": {"status": 200, "msg": "OK"}
            }
        """
    }
}
