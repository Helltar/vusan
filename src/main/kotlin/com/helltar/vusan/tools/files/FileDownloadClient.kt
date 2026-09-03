package com.helltar.vusan.tools.files

import com.helltar.vusan.common.sanitizeFilename
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Telegram caps bot document uploads at 50 MB, so a larger download could never be delivered. */
internal const val MAX_DOWNLOAD_MB = 50

internal const val MAX_DOWNLOAD_BYTES = MAX_DOWNLOAD_MB * 1024L * 1024

private const val MAX_REDIRECTS = 5
private const val READ_CHUNK_BYTES = 64 * 1024
private const val INITIAL_BUFFER_BYTES = 64 * 1024
private const val DEFAULT_DOWNLOAD_NAME = "download"
private const val MAX_EXTENSION_CHARS = 5

private val DOWNLOAD_TIMEOUT = 3.minutes
private val SOCKET_TIMEOUT = 30.seconds
private val ALLOWED_PROTOCOLS = setOf("http", "https")

// many CDNs, wikis and file hosts answer a default ktor user agent with 403
private const val USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

sealed class FileDownloadResult {

    class Success(val bytes: ByteArray, val filename: String) : FileDownloadResult()

    /** [sizeBytes] is the declared `Content-Length`, or `null` when the cap tripped mid-stream. */
    class TooLarge(val sizeBytes: Long?) : FileDownloadResult()
}

class FileDownloadClient(http: HttpClient) {

    private companion object {
        val log = KotlinLogging.logger {}
    }

    // redirects are followed by hand so every hop is re-checked against the local-address guard;
    // a public URL that 302s to 127.0.0.1 or 169.254.169.254 would otherwise walk straight past it.
    // statuses are inspected here too, so the shared client's expectSuccess is turned off.
    private val http =
        http.config {
            followRedirects = false
            expectSuccess = false
        }

    suspend fun download(
        url: String,
        requestedFilename: String = "",
        maxBytes: Long = MAX_DOWNLOAD_BYTES
    ): FileDownloadResult {
        var target = parseDownloadUrl(url)

        repeat(MAX_REDIRECTS + 1) {
            requirePublicHost(target)

            when (val hop = fetch(target, requestedFilename, maxBytes)) {
                is Hop.Done -> return hop.result
                is Hop.Redirect -> target = URLBuilder(target).takeFrom(hop.location).build()
            }
        }

        error("Gave up after $MAX_REDIRECTS redirects")
    }

    private sealed interface Hop {
        class Done(val result: FileDownloadResult) : Hop
        class Redirect(val location: String) : Hop
    }

    private suspend fun fetch(target: Url, requestedFilename: String, maxBytes: Long): Hop =
        http.prepareGet {
            url(target)
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.Accept, "*/*")

            timeout {
                requestTimeoutMillis = DOWNLOAD_TIMEOUT.inWholeMilliseconds
                socketTimeoutMillis = SOCKET_TIMEOUT.inWholeMilliseconds
            }
        }.execute { response ->
            when {
                response.status.isRedirect -> {
                    val location = response.headers[HttpHeaders.Location]

                    require(!location.isNullOrBlank()) {
                        "HTTP ${response.status.value} from ${target.host} without a Location header"
                    }

                    log.info { "download redirect ${response.status.value} host=[${target.host}]" }

                    Hop.Redirect(location)
                }

                !response.status.isSuccess() -> error("HTTP ${response.status.value} from ${target.host}")

                else -> Hop.Done(readCapped(response, target, requestedFilename, maxBytes))
            }
        }

    private suspend fun readCapped(
        response: HttpResponse,
        target: Url,
        requestedFilename: String,
        maxBytes: Long
    ): FileDownloadResult {
        val declared = response.contentLength()

        if (declared != null && declared > maxBytes) {
            log.info { "download rejected by content-length host=[${target.host}] bytes=$declared max=$maxBytes" }
            return FileDownloadResult.TooLarge(declared)
        }

        val channel = response.bodyAsChannel()
        val buffer = ByteArrayOutputStream(declared?.toInt() ?: INITIAL_BUFFER_BYTES)
        val chunk = ByteArray(READ_CHUNK_BYTES)

        while (true) {
            val read = channel.readAvailable(chunk)

            if (read < 0) break

            // a missing or lying Content-Length only shows up here, so the cap is enforced on the stream too
            if (buffer.size() + read > maxBytes) {
                channel.cancel()
                log.info { "download exceeded cap mid-stream host=[${target.host}] max=$maxBytes" }
                return FileDownloadResult.TooLarge(null)
            }

            buffer.write(chunk, 0, read)
        }

        val bytes = buffer.toByteArray()

        require(bytes.isNotEmpty()) { "Downloaded file from ${target.host} is empty" }

        val filename = resolveFilename(requestedFilename, target, response)

        log.info { "download ok host=[${target.host}] filename=[$filename] bytes=${bytes.size}" }

        return FileDownloadResult.Success(bytes, filename)
    }

    private fun resolveFilename(requested: String, target: Url, response: HttpResponse): String {
        val candidates =
            sequenceOf(
                requested.sanitizeFilename(),
                response.contentDispositionFilename(),
                target.segments.lastOrNull().orEmpty().sanitizeFilename(),
                target.host.replace('.', '-').sanitizeFilename()
            )

        val name = candidates.firstOrNull { it.isNotBlank() } ?: DEFAULT_DOWNLOAD_NAME

        return name.withExtensionFor(response.contentType())
    }

    /**
     * URLs reach this client straight from chat text, so a caller can aim the bot at its own network.
     * Resolve the host up front and refuse loopback, link-local (cloud metadata endpoints), and private
     * ranges — the bot process shares a network with the sandbox service and the database.
     */
    private suspend fun requirePublicHost(target: Url) {
        val addresses =
            withContext(Dispatchers.IO) { runCatching { InetAddress.getAllByName(target.host) }.getOrNull() }

        requireNotNull(addresses) { "Could not resolve host [${target.host}]" }

        require(addresses.isNotEmpty() && addresses.none { it.isPrivateOrLocal }) {
            "Refusing to download from a private or local address: [${target.host}]"
        }
    }
}

private fun parseDownloadUrl(url: String): Url {
    val trimmed = url.trim()

    require(trimmed.isNotEmpty()) { "Download URL must not be empty" }

    // models routinely drop the scheme ("example.com/report.pdf"); assume https rather than fail
    val text = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val parsed = runCatching { Url(text) }.getOrNull()

    requireNotNull(parsed) { "Not a valid URL: [$trimmed]" }

    require(parsed.protocol.name in ALLOWED_PROTOCOLS) {
        "Only http and https URLs can be downloaded, got [${parsed.protocol.name}]"
    }

    require(parsed.host.isNotBlank()) { "URL has no host: [$trimmed]" }

    return parsed
}

private val HttpStatusCode.isRedirect: Boolean
    get() = value in 300..399

private val InetAddress.isPrivateOrLocal: Boolean
    get() = isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress ||
            isMulticastAddress || isUniqueLocalIpv6

// java exposes no predicate for ipv6 unique-local (fc00::/7), the range docker and most private ipv6
// networks actually use; isSiteLocalAddress only covers the deprecated fec0::/10.
private val InetAddress.isUniqueLocalIpv6: Boolean
    get() = this is Inet6Address && (address.first().toInt() and 0xfe) == 0xfc

private fun HttpResponse.contentDispositionFilename(): String =
    headers[HttpHeaders.ContentDisposition]
        ?.let { runCatching { ContentDisposition.parse(it) }.getOrNull() }
        ?.parameter(ContentDisposition.Parameters.FileName)
        ?.sanitizeFilename()
        .orEmpty()

// ktor's ContentType.fileExtensions() walks a reverse mime map and answers text/html with "acgi",
// so name the common types by hand; anything else falls back to its subtype when that reads like an
// extension, which covers pdf/zip/json/png and leaves compound subtypes (octet-stream) unnamed.
private val EXTENSION_BY_MIME =
    mapOf(
        "text/html" to "html",
        "text/plain" to "txt",
        "text/markdown" to "md",
        "text/javascript" to "js",
        "image/jpeg" to "jpg",
        "image/svg+xml" to "svg",
        "audio/mpeg" to "mp3",
        "application/javascript" to "js",
        "application/x-tar" to "tar",
        "application/gzip" to "gz",
        "application/msword" to "doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx"
    )

private fun String.withExtensionFor(contentType: ContentType?): String {
    if (hasFileExtension || contentType == null) return this

    val mime = "${contentType.contentType}/${contentType.contentSubtype}".lowercase()
    val extension = EXTENSION_BY_MIME[mime] ?: contentType.contentSubtype.lowercase().takeIf { it.looksLikeExtension }

    return if (extension == null) this else "$this.$extension"
}

private val String.looksLikeExtension: Boolean
    get() = isNotEmpty() && length <= MAX_EXTENSION_CHARS && all(Char::isLetterOrDigit)

internal val String.hasFileExtension: Boolean
    get() = substringAfterLast('.', "").looksLikeExtension
