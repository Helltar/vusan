package com.helltar.vusan.tools.giphy

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*

class GiphyClient(private val http: HttpClient, private val apiKey: String) {

    suspend fun search(query: String, limit: Int = 1, rating: String = "g"): GiphySearchResponse {
        require(query.isNotBlank()) { "Query must not be blank" }

        val body: GiphySearchResponse =
            http.get("https://api.giphy.com/v1/gifs/search") {
                parameter("api_key", apiKey)
                parameter("q", query)
                parameter("limit", limit)
                parameter("rating", rating)
            }.body()

        check(body.meta.status in 200..299) { "Giphy API error: ${body.meta.msg}" }

        return body
    }
}
