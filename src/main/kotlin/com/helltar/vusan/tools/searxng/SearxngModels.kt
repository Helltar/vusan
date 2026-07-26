package com.helltar.vusan.tools.searxng

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearxngResponse(
    val results: List<SearxngResult> = emptyList(),
    val answers: List<SearxngAnswer> = emptyList(),
    val infoboxes: List<SearxngInfobox> = emptyList(),

    // pairs of engine name and the reason it dropped out; engines suspend themselves on rate limits
    // and CAPTCHAs, so this is the only way to tell an empty result set from a silently degraded one.
    @SerialName("unresponsive_engines") val unresponsiveEngines: List<List<String>> = emptyList()
)

@Serializable
data class SearxngResult(
    val url: String = "",
    val title: String = "",
    val content: String = "",
    val engine: String = "",
    val publishedDate: String? = null,
    @SerialName("img_src") val imageUrl: String = ""
)

/** A direct answer an engine returned instead of a link; absent for most queries. */
@Serializable
data class SearxngAnswer(val answer: String = "")

@Serializable
data class SearxngInfobox(val infobox: String = "", val content: String = "")
