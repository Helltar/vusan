package com.helltar.vusan.tools.exa

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

class ExaClient(private val http: HttpClient, private val apiKey: String) {

    suspend fun search(
        query: String,
        numResults: Int,
        highlightChars: Int,
        category: String? = null,
        startPublishedDate: String? = null,
    ): ExaSearchResponse {
        require(query.isNotBlank()) { "Query must not be blank" }
        require(numResults in 1..MAX_RESULTS) { "numResults must be between 1 and $MAX_RESULTS" }

        return http.post("$BASE_URL/search") {
            header(API_KEY_HEADER, apiKey)
            contentType(ContentType.Application.Json)
            setBody(
                ExaSearchRequest(
                    query = query,
                    type = SEARCH_TYPE,
                    numResults = numResults,
                    category = category,
                    startPublishedDate = startPublishedDate,
                    contents = ExaSearchContents(highlights = ExaHighlights(highlightChars)),
                ),
            )
        }.body()
    }

    /** A search that returns, for each page found, its preview picture and up to [imagesPerPage] of its images. */
    suspend fun searchImages(query: String, numResults: Int, imagesPerPage: Int): ExaSearchResponse {
        require(query.isNotBlank()) { "Query must not be blank" }
        require(numResults in 1..MAX_RESULTS) { "numResults must be between 1 and $MAX_RESULTS" }

        return http.post("$BASE_URL/search") {
            header(API_KEY_HEADER, apiKey)
            contentType(ContentType.Application.Json)
            setBody(
                ExaSearchRequest(
                    query = query,
                    type = SEARCH_TYPE,
                    numResults = numResults,
                    contents = ExaSearchContents(extras = ExaExtras(imagesPerPage)),
                ),
            )
        }.body()
    }

    suspend fun contents(url: String, maxChars: Int): ExaContentsResponse {
        require(url.isNotBlank()) { "URL must not be blank" }

        return http.post("$BASE_URL/contents") {
            header(API_KEY_HEADER, apiKey)
            contentType(ContentType.Application.Json)
            setBody(ExaContentsRequest(urls = listOf(url), text = ExaText(maxChars)))
        }.body()
    }

    private companion object {
        const val BASE_URL = "https://api.exa.ai"
        const val API_KEY_HEADER = "x-api-key"
        const val MAX_RESULTS = 10

        // `lite` leaves out every result whose page exa has not cached, and a chat question is often about a
        // page too new for the cache
        const val SEARCH_TYPE = "auto"
    }
}
