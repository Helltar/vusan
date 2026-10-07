package com.helltar.vusan.tools.klipy

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class KlipyClient(private val http: HttpClient, private val apiKey: String) {

    // no content filter and no locale are sent: what a deployment's results may hold is set in the
    // provider's own dashboard, and a second copy of that here could only disagree with it.
    suspend fun search(kind: KlipyKind, query: String, perPage: Int): List<KlipyItem> {
        require(query.isNotBlank()) { "Query must not be blank" }

        val body: KlipySearchResponse =
            http.get("$BASE_URL/$apiKey/${kind.path}/search") {
                parameter("q", query)
                parameter("per_page", perPage)
            }.body()

        check(body.result) { "KLIPY search failed" }

        return body.data.data
    }

    /** Tells the provider an item found by [query] was sent, which is what it ranks its results on. */
    suspend fun reportShare(kind: KlipyKind, slug: String, query: String) {
        http.post("$BASE_URL/$apiKey/${kind.path}/share/${slug.encodeURLPathPart()}") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("q", query) })
        }
    }

    private companion object {
        const val BASE_URL = "https://api.klipy.com/api/v1"
    }
}
