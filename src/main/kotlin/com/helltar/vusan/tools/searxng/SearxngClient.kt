package com.helltar.vusan.tools.searxng

import com.helltar.vusan.common.rethrowIfCancellation
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException

class SearxngClient(private val http: HttpClient, baseUrl: String) {

    private companion object {
        // returned when the SearXNG instance can't be reached at all (container down, wrong URL).
        // framed so the model reports it instead of retrying the same lookup.
        const val UNREACHABLE_MESSAGE =
            "The search service is not reachable right now, so the search did not run. " +
                    "Tell the user web search is temporarily unavailable; do not retry."
    }

    private val searchUrl = baseUrl.trimEnd('/') + "/search"

    /**
     * Every optional parameter is nullable rather than blank on purpose: SearXNG answers an empty
     * `language` with `400 Empty language parameter`, and an unknown `time_range` with a 400 too.
     * Callers must map "not set" to `null` and validate [timeRange] before calling.
     */
    suspend fun search(
        query: String,
        categories: String? = null,
        engines: String? = null,
        timeRange: String? = null,
        language: String? = null
    ): SearxngResponse {
        require(query.isNotBlank()) { "Query must not be blank" }

        return runCatching {
            http.get(searchUrl) {
                parameter("q", query)
                parameter("format", "json")
                parameter("categories", categories)
                parameter("engines", engines)
                parameter("time_range", timeRange)
                parameter("language", language)
            }.body<SearxngResponse>()
        }.getOrElse { e ->
            e.rethrowIfCancellation()

            when (e) {
                is ConnectException, is UnresolvedAddressException -> throw IllegalStateException(UNREACHABLE_MESSAGE, e)
                else -> throw e
            }
        }
    }
}
