package com.helltar.vusan.tools.images

import com.helltar.vusan.infra.Http
import com.helltar.vusan.tools.files.FileDownloadClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ImageDownloadClientTest {
    private val publicUrl = "http://93.184.216.34/image.png"
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aS9sAAAAASUVORK5CYII="
    )

    @Test
    fun `public images still download through the bounded file pipeline`() = runBlocking {
        Http.createClient(MockEngine { respond(png) }).use { http ->
            assertContentEquals(png, ImageDownloadClient(FileDownloadClient(http)).download(publicUrl))
        }
    }

    @Test
    fun `image redirects cannot read a private workspace file`() = runBlocking {
        var requests = 0
        Http.createClient(MockEngine {
            requests++
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://127.0.0.1:8080/files?id=u42&path=private.png"))
        }).use { http ->
            assertFailsWith<IllegalArgumentException> { ImageDownloadClient(FileDownloadClient(http)).download(publicUrl) }
        }
        assertEquals(1, requests)
    }

    @Test
    fun `private image URLs are rejected before any request`() = runBlocking {
        Http.createClient(MockEngine { error("No network request expected") }).use { http ->
            assertFailsWith<IllegalArgumentException> {
                ImageDownloadClient(FileDownloadClient(http)).download("http://192.168.1.9/private.png")
            }
        }
        Unit
    }

    @Test
    fun `image size is capped during streaming without content length`() = runBlocking {
        Http.createClient(MockEngine { respond(ByteReadChannel(ByteArray(MAX_PHOTO_BYTES + 1))) }).use { http ->
            assertNull(ImageDownloadClient(FileDownloadClient(http)).download(publicUrl))
        }
    }

    @Test
    fun `declared oversized image is refused`() = runBlocking {
        Http.createClient(MockEngine {
            respond(png, headers = headersOf(HttpHeaders.ContentLength, (MAX_PHOTO_BYTES + 1).toString()))
        }).use { http ->
            assertNull(ImageDownloadClient(FileDownloadClient(http)).download(publicUrl))
        }
    }
}
