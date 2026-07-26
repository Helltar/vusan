package com.helltar.vusan.tools.tavily

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*

class TavilyClient(private val http: HttpClient, private val apiKey: String) {

    suspend fun search(
        query: String,
        maxResults: Int = 5,
        includeImages: Boolean = false,
        topic: String? = null,
        timeRange: String? = null,
        excludeDomains: List<String> = emptyList()
    ): SearchResponse {
        require(query.isNotBlank()) { "Query must not be blank" }
        require(maxResults in 1..10) { "maxResults must be between 1 and 10" }

        return http.post("https://api.tavily.com/search") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(
                SearchRequest(
                    query = query,
                    maxResults = maxResults,
                    includeImages = includeImages,
                    includeImageDescriptions = includeImages,
                    topic = topic,
                    timeRange = timeRange,
                    excludeDomains = excludeDomains
                )
            )
        }.body()
    }

    suspend fun extract(url: String): ExtractResponse {
        require(url.isNotBlank()) { "URL must not be blank" }

        return http.post("https://api.tavily.com/extract") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(ExtractRequest(urls = listOf(url)))
        }.body()
    }
}
