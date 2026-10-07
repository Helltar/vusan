package com.helltar.vusan.tools.klipy

import com.helltar.vusan.infra.Http
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.ChatCapabilities
import com.helltar.vusan.tools.files.FileDownloadClient
import com.helltar.vusan.tools.toolFailure
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KlipyToolsTest {

    private val requests = mutableListOf<HttpRequestData>()
    private var shareStatus = HttpStatusCode.OK

    @Test
    fun `a search lists candidates in the provider's order and queues nothing`() = runBlocking {
        val outbox = BotOutbox()
        val result = tools(outbox).searchGifs("sleepy cat")

        assertTrue(result.indexOf("sleepy-cat--a1: Sleepy Cat") < result.indexOf("blinking-owl--b2: Blinking Owl"))
        assertTrue("lost-fox--c3" !in result, "an item without a file is not offered")
        assertTrue(outbox.pending.isEmpty())

        val search = requests.single()
        assertEquals("/api/v1/test-key/gifs/search", search.url.encodedPath)
        assertEquals("sleepy cat", search.url.parameters["q"])
        assertEquals("8", search.url.parameters["per_page"])
    }

    @Test
    fun `a GIF is sent by the address the provider gave and reported as shared`() = runBlocking {
        val outbox = BotOutbox()
        val tools = tools(outbox)

        tools.searchGifs("sleepy cat")
        tools.sendGif("sleepy-cat--a1")

        val animation = assertIs<BotOutput.Animation>(outbox.pending.single().output)
        assertEquals("https://static.klipy.example.test/a1/hd.mp4?t=1", animation.url)

        val share = requests.last()
        assertEquals(HttpMethod.Post, share.method)
        assertEquals("/api/v1/test-key/gifs/share/sleepy-cat--a1", share.url.encodedPath)
        assertContains(share.body.toByteArray().decodeToString(), """"q":"sleepy cat"""")
    }

    @Test
    fun `a meme is sent as a picture and a clip as a video`() = runBlocking {
        val outbox = BotOutbox()
        val tools = tools(outbox)

        assertContains(tools.searchGifs("slow morning", kind = "meme"), "slow-morning--m1: Slow Morning")
        tools.sendGif("slow-morning--m1")
        assertContains(tools.searchGifs("slow morning", kind = "clip"), "slow-clap--v1: Slow Clap")
        tools.sendGif("slow-clap--v1")

        val (photo, video) = outbox.pending.map { it.output }

        assertContentEquals(MEME_BYTES, assertIs<BotOutput.Photo>(photo).bytes)
        assertContentEquals(CLIP_BYTES, assertIs<BotOutput.Video>(video).bytes)
        assertTrue(requests.any { it.url.encodedPath == "/api/v1/test-key/static-memes/search" })
        assertTrue(requests.any { it.url.encodedPath == "/api/v1/test-key/clips/share/slow-clap--v1" })
    }

    @Test
    fun `the same candidate is not sent twice in a turn`() = runBlocking {
        val outbox = BotOutbox()
        val tools = tools(outbox)

        tools.searchGifs("sleepy cat")
        tools.sendGif("sleepy-cat--a1")

        assertContains(tools.searchGifs("drowsy kitten"), "sleepy-cat--a1: Sleepy Cat (already sent in this turn)")
        assertContains(toolFailure { tools.sendGif("sleepy-cat--a1") }, "already sent")
        assertEquals(1, outbox.pending.size)
    }

    @Test
    fun `an id no search returned and a kind nobody offers are rejected`() = runBlocking {
        val outbox = BotOutbox()
        val tools = tools(outbox)

        tools.searchGifs("sleepy cat")

        assertContains(toolFailure { tools.sendGif("https://static.klipy.example.test/other.mp4") }, "unknown id")
        assertContains(toolFailure { tools.searchGifs("sleepy cat", kind = "sticker") }, "unknown kind")
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun `a kind the chat does not take is refused before the search`() = runBlocking {
        val tools = tools(BotOutbox(ChatCapabilities(photos = false)))

        assertContains(toolFailure { tools.searchGifs("slow morning", kind = "meme") }, "does not accept a meme")
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a share report that fails does not undo the send`() = runBlocking {
        shareStatus = HttpStatusCode.InternalServerError

        val outbox = BotOutbox()
        val tools = tools(outbox)

        tools.searchGifs("sleepy cat")

        assertContains(tools.sendGif("sleepy-cat--a1"), "queued")
        assertEquals(1, outbox.pending.size)
    }

    private fun tools(outbox: BotOutbox): KlipyTools {
        val engine =
            MockEngine { request ->
                requests += request
                val path = request.url.encodedPath

                when {
                    path.endsWith("/gifs/search") -> respondJson(GIFS)
                    path.endsWith("/static-memes/search") -> respondJson(MEMES)
                    path.endsWith("/clips/search") -> respondJson(CLIPS)
                    "/share/" in path -> respondJson("""{"result":true}""", shareStatus)
                    else -> error("unexpected request: $path")
                }
            }

        val media =
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/m1/hd.png" -> respond(MEME_BYTES, headers = headersOf(HttpHeaders.ContentType, "image/png"))
                    "/v1/clip.mp4" -> respond(CLIP_BYTES, headers = headersOf(HttpHeaders.ContentType, "video/mp4"))
                    else -> error("unexpected download: ${request.url}")
                }
            }

        return KlipyTools(KlipyClient(Http.createClient(engine), "test-key"), FileDownloadClient(HttpClient(media)), outbox)
    }

    private fun MockRequestHandleScope.respondJson(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

    private companion object {
        val MEME_BYTES = byteArrayOf(1, 2, 3)
        val CLIP_BYTES = byteArrayOf(4, 5, 6, 7)

        const val GIFS = """
            {"result":true,"data":{"data":[
              {"id":"1","slug":"sleepy-cat--a1","title":"Sleepy Cat","type":"gif","file":{
                "hd":{"gif":{"url":"https://static.klipy.example.test/a1/hd.gif"},
                      "mp4":{"url":"https://static.klipy.example.test/a1/hd.mp4?t=1"}}}},
              {"id":"2","slug":"blinking-owl--b2","title":"Blinking Owl","type":"gif","file":{
                "md":{"gif":{"url":"https://static.klipy.example.test/b2/md.gif"}}}},
              {"id":"3","slug":"lost-fox--c3","title":"Lost Fox","type":"gif","file":{}}
            ],"current_page":1,"per_page":8,"has_next":true}}
        """

        const val MEMES = """
            {"result":true,"data":{"data":[
              {"id":4,"slug":"slow-morning--m1","title":"Slow Morning","type":"static_meme","file":{
                "hd":{"png":{"url":"https://static.klipy.example.test/m1/hd.png"}}}}
            ]}}
        """

        const val CLIPS = """
            {"result":true,"data":{"data":[
              {"id":"5","slug":"slow-clap--v1","title":"Slow Clap","type":"clip","file":{
                "mp4":"https://static.klipy.example.test/v1/clip.mp4","gif":"https://static.klipy.example.test/v1/clip.gif"}}
            ]}}
        """
    }
}
