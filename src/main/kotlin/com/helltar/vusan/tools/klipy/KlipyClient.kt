package com.helltar.vusan.tools.klipy

import com.helltar.vusan.common.rethrowIfCancellation
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class KlipyClient(private val http: HttpClient, private val apiKey: String) {

    // no content filter or locale: the deployment sets those in the provider's dashboard, and a second copy
    // could only disagree.
    suspend fun search(kind: KlipyKind, query: String, perPage: Int): List<KlipyItem> {
        require(query.isNotBlank()) { "Query must not be blank" }

        val body: KlipySearchResponse =
            withoutTheKey("search") {
                http.get("$BASE_URL/$apiKey/${kind.path}/search") {
                    parameter("q", query)
                    parameter("per_page", perPage)
                }.body()
            }

        check(body.result) { "KLIPY search failed" }

        return body.data.data
    }

    /** Tells the provider an item found by [query] was sent, which its ranking rests on. */
    suspend fun reportShare(kind: KlipyKind, slug: String, query: String) {
        withoutTheKey("share report") {
            http.post("$BASE_URL/$apiKey/${kind.path}/share/${slug.encodeURLPathPart()}") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("q", query) })
            }
        }
    }

    // the key is in the path, and ktor spells the whole url into timeouts and unread bodies, which reach the
    // model and the log. the message is kept with the key blanked; the cause is dropped, since a stack trace
    // prints every message on the chain.
    private suspend fun <T> withoutTheKey(what: String, block: suspend () -> T): T =
        try {
            block()
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            val reason = t.message?.replace(apiKey, REDACTED_KEY) ?: t::class.simpleName
            error("KLIPY $what failed: $reason")
        }

    private companion object {
        const val BASE_URL = "https://api.klipy.com/api/v1"
        const val REDACTED_KEY = "***"
    }
}
