package com.helltar.vusan.tools.exa

import kotlinx.serialization.Serializable

@Serializable
data class ExaSearchRequest(
    val query: String,
    val type: String,
    // no default: the shared json omits defaults, and exa's own is 10
    val numResults: Int,
    val category: String? = null,
    val startPublishedDate: String? = null,
    val contents: ExaSearchContents,
)

@Serializable
data class ExaSearchContents(val highlights: ExaHighlights)

@Serializable
data class ExaHighlights(val maxCharacters: Int)

@Serializable
data class ExaContentsRequest(
    val urls: List<String>,
    val text: ExaText,
)

@Serializable
data class ExaText(val maxCharacters: Int)

@Serializable
data class ExaSearchResponse(val results: List<ExaResult> = emptyList())

@Serializable
data class ExaContentsResponse(
    val results: List<ExaResult> = emptyList(),
    val statuses: List<ExaStatus> = emptyList(),
)

@Serializable
data class ExaResult(
    val url: String,
    val title: String? = null,
    val publishedDate: String? = null,
    val highlights: List<String> = emptyList(),
    val text: String? = null,
)

@Serializable
data class ExaStatus(
    val id: String,
    val status: String,
    val error: ExaStatusError? = null,
)

@Serializable
data class ExaStatusError(
    val tag: String? = null,
    val httpStatusCode: Int? = null,
)
