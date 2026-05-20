package com.helltar.vusan.tools.tavily

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchRequest(
    val query: String,
    @SerialName("max_results") val maxResults: Int = 5,
    @SerialName("search_depth") val searchDepth: String = "advanced",
    val topic: String? = null,
    @SerialName("time_range") val timeRange: String? = null,
    @SerialName("include_images") val includeImages: Boolean = false,
    @SerialName("include_image_descriptions") val includeImageDescriptions: Boolean = false
)

@Serializable
data class SearchResponse(
    val results: List<SearchResult> = emptyList(),
    val images: List<TavilyImage> = emptyList()
)

@Serializable
data class TavilyImage(val url: String, val description: String? = null)

@Serializable
data class SearchResult(
    val title: String,
    val url: String,
    val content: String,
    @SerialName("published_date") val publishedDate: String? = null
)

@Serializable
data class ExtractRequest(val urls: List<String>)

@Serializable
data class ExtractResponse(
    val results: List<ExtractResult> = emptyList(),
    @SerialName("failed_results") val failedResults: List<FailedResult> = emptyList()
)

@Serializable
data class ExtractResult(
    val url: String,
    @SerialName("raw_content") val rawContent: String
)

@Serializable
data class FailedResult(
    val url: String,
    val error: String
)
