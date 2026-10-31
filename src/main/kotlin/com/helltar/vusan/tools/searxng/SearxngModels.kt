package com.helltar.vusan.tools.searxng

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearxngResponse(
    val results: List<SearxngResult> = emptyList(),
    val answers: List<SearxngAnswer> = emptyList(),
    val infoboxes: List<SearxngInfobox> = emptyList(),

    // engine name and dropout reason pairs; engines suspend themselves on rate limits and CAPTCHAs, so this
    // is the only way to tell an empty result from a silently degraded one.
    @SerialName("unresponsive_engines") val unresponsiveEngines: List<List<String>> = emptyList(),
)

@Serializable
data class SearxngResult(
    val url: String = "",
    val title: String = "",
    val content: String = "",
    val publishedDate: String? = null,
    @SerialName("img_src") val imageUrl: String = "",
)

/** A direct answer an engine returned instead of a link; absent for most queries. */
@Serializable
data class SearxngAnswer(val answer: String = "")

@Serializable
data class SearxngInfobox(val infobox: String = "", val content: String = "")
