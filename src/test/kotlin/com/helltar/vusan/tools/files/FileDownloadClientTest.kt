package com.helltar.vusan.tools.files

import com.helltar.vusan.infra.Http
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

// literal addresses keep the private-address guard on its real code path without a DNS lookup
private const val PUBLIC_HOST = "93.184.216.34"

class FileDownloadClientTest {

    private fun client(handler: MockRequestHandler) = FileDownloadClient(Http.createClient(MockEngine(handler)))

    @Test
    fun `downloads file and prefers the content-disposition filename`() = runBlocking {
        val payload = "%PDF-1.7 body".toByteArray()

        val client =
            client {
                respond(
                    content = payload,
                    headers =
                        headersOf(
                            HttpHeaders.ContentType to listOf("application/pdf"),
                            HttpHeaders.ContentDisposition to listOf("""attachment; filename="annual report.pdf"""")
                        )
                )
            }

        val result = assertIs<FileDownloadResult.Success>(client.download("https://$PUBLIC_HOST/dl?id=7"))

        assertEquals("annual report.pdf", result.filename)
        assertContentEquals(payload, result.bytes)
    }

    @Test
    fun `names an extensionless page from the url path and content type`() = runBlocking {
        val client =
            client {
                respond(
                    content = "<html></html>".toByteArray(),
                    headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")
                )
            }

        val result = assertIs<FileDownloadResult.Success>(client.download("https://$PUBLIC_HOST/wiki/article"))

        assertEquals("article.html", result.filename)
    }

    @Test
    fun `falls back to the host when the url has no path`() = runBlocking {
        val client =
            client {
                respond(content = "<html></html>".toByteArray(), headers = headersOf(HttpHeaders.ContentType, "text/html"))
            }

        val result = assertIs<FileDownloadResult.Success>(client.download("https://$PUBLIC_HOST/"))

        assertEquals("93-184-216-34.html", result.filename)
    }

    @Test
    fun `leaves a name unchanged when the content type implies no extension`() = runBlocking {
        val client =
            client {
                respond(
                    content = ByteArray(4),
                    headers = headersOf(HttpHeaders.ContentType, "application/octet-stream")
                )
            }

        val result = assertIs<FileDownloadResult.Success>(client.download("https://$PUBLIC_HOST/blobs/payload"))

        assertEquals("payload", result.filename)
    }

    @Test
    fun `an explicit filename wins over the server name and is sanitized`() = runBlocking {
        val client =
            client {
                respond(
                    content = "x".toByteArray(),
                    headers =
                        headersOf(
                            HttpHeaders.ContentType to listOf("application/pdf"),
                            HttpHeaders.ContentDisposition to listOf("""attachment; filename="server.pdf"""")
                        )
                )
            }

        val result =
            assertIs<FileDownloadResult.Success>(
                client.download("https://$PUBLIC_HOST/x", requestedFilename = "../../etc/my report.pdf")
            )

        assertEquals("my report.pdf", result.filename)
    }

    @Test
    fun `rejects a declared content-length above the cap without reading the body`() = runBlocking {
        val client =
            client {
                respond(content = ByteArray(64), headers = headersOf(HttpHeaders.ContentLength, "999999"))
            }

        val result = assertIs<FileDownloadResult.TooLarge>(client.download("https://$PUBLIC_HOST/big.iso", maxBytes = 100))

        assertEquals(999_999, result.sizeBytes)
    }

    @Test
    fun `caps mid-stream when the server declares no content-length`() = runBlocking {
        val client = client { respond(content = ByteReadChannel(ByteArray(4_096))) }

        val result = assertIs<FileDownloadResult.TooLarge>(client.download("https://$PUBLIC_HOST/big.iso", maxBytes = 100))

        assertNull(result.sizeBytes)
    }

    @Test
    fun `follows a redirect and downloads the final target`() = runBlocking {
        val client =
            client { request ->
                if (request.url.encodedPath == "/short") {
                    respond(
                        content = "",
                        status = HttpStatusCode.Found,
                        headers = headersOf(HttpHeaders.Location, "https://$PUBLIC_HOST/final/data.csv")
                    )
                } else {
                    respond(content = "a,b".toByteArray(), headers = headersOf(HttpHeaders.ContentType, "text/csv"))
                }
            }

        val result = assertIs<FileDownloadResult.Success>(client.download("https://$PUBLIC_HOST/short"))

        assertEquals("data.csv", result.filename)
    }

    @Test
    fun `refuses a redirect that points at a local address`() = runBlocking {
        val client =
            client {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "http://169.254.169.254/latest/meta-data/")
                )
            }

        val error = assertFailsWith<IllegalArgumentException> { client.download("https://$PUBLIC_HOST/short") }

        assertContains(error.message.orEmpty(), "private or local address")
    }

    @Test
    fun `redirect destinations cannot change to a non-http scheme`() = runBlocking {
        val client = client {
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "file:///etc/passwd"))
        }
        val error = assertFailsWith<IllegalArgumentException> { client.download("https://$PUBLIC_HOST/short") }
        assertContains(error.message.orEmpty(), "Only http and https")
    }

    @Test
    fun `refuses loopback hosts`() = runBlocking {
        val client = client { respond(content = "secret".toByteArray()) }

        val error = assertFailsWith<IllegalArgumentException> { client.download("http://127.0.0.1:9090/metrics") }

        assertContains(error.message.orEmpty(), "private or local address")
    }

    @Test
    fun `refuses non-http schemes`() = runBlocking {
        val client = client { respond(content = "x".toByteArray()) }

        val error = assertFailsWith<IllegalArgumentException> { client.download("file:///etc/passwd") }

        assertContains(error.message.orEmpty(), "Only http and https")
    }

    @Test
    fun `assumes https when the url has no scheme`() = runBlocking {
        val client =
            client { request ->
                assertEquals(URLProtocol.HTTPS, request.url.protocol)
                respond(content = "x".toByteArray(), headers = headersOf(HttpHeaders.ContentType, "text/plain"))
            }

        assertIs<FileDownloadResult.Success>(client.download("$PUBLIC_HOST/notes.txt"))
        Unit
    }

    @Test
    fun `reports the failing status without leaking the query`() = runBlocking {
        val client = client { respond(content = "nope", status = HttpStatusCode.NotFound) }

        val error = assertFailsWith<IllegalStateException> { client.download("https://$PUBLIC_HOST/x?token=secret") }

        assertEquals("HTTP 404 from $PUBLIC_HOST", error.message)
    }
}
