package com.helltar.vusan.tools.sites

import com.helltar.vusan.common.rethrowIfCancellation
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException

class SiteClient(
    http: HttpClient,
    baseUrl: String,
    private val token: String
) {
    init {
        require(token.isNotBlank()) { "Site API authentication is required" }
    }

    // the configured API has no redirect contract; never relay its bearer secret to another origin.
    private val http = http.config { followRedirects = false }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 120_000L
    }

    private val base = baseUrl.trimEnd('/')

    suspend fun startUpload(owner: String): UploadStarted = reachable {
        http.post("$base/uploads") {
            siteRequest()
            parameter("owner", owner)
        }.requireSuccess().body()
    }

    suspend fun upload(uploadId: String, path: String, bytes: ByteArray): UploadedFile = reachable {
        http.put("$base/uploads/$uploadId") {
            siteRequest()
            parameter("path", path)
            setBody(bytes)
        }.requireSuccess().body()
    }

    suspend fun commit(uploadId: String): SiteRecord = reachable {
        http.post("$base/uploads/$uploadId/commit") { siteRequest() }.requireSuccess().body()
    }

    suspend fun discard(uploadId: String) {
        reachable { http.delete("$base/uploads/$uploadId") { siteRequest() }.requireSuccess() }
    }

    suspend fun status(owner: String): SiteStatus = reachable {
        http.get("$base/site") {
            siteRequest()
            parameter("owner", owner)
        }.requireSuccess().body()
    }

    suspend fun remove(owner: String): Boolean = reachable {
        http.delete("$base/site") {
            siteRequest()
            parameter("owner", owner)
        }.requireSuccess().body<SiteRemoved>().removed
    }

    private fun HttpRequestBuilder.siteRequest() {
        bearerAuth(token)
        expectSuccess = false
        timeout {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    private suspend fun HttpResponse.requireSuccess(): HttpResponse {
        check(status.isSuccess()) { body<SiteError>().error }
        return this
    }

    private suspend fun <T> reachable(block: suspend () -> T): T =
        runCatching { block() }.getOrElse { e ->
            e.rethrowIfCancellation()
            when (e) {
                is ConnectException, is UnresolvedAddressException ->
                    error("The site host is temporarily unavailable. Tell the user; do not retry immediately.")
                else -> throw e
            }
        }
}
