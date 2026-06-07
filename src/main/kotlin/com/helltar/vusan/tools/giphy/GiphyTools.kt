package com.helltar.vusan.tools.giphy

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.tools.suspendToolGuard

@Suppress("unused")
class GiphyTools(private val client: GiphyClient, private val outbox: BotOutbox) : ToolSet {

    @Tool
    @LLMDescription(GiphyToolDescriptions.SEARCH_GIF)
    suspend fun searchGif(
        @LLMDescription(GiphyToolDescriptions.QUERY)
        query: String,
        @LLMDescription(GiphyToolDescriptions.RATING)
        rating: String = "g"
    ): String = suspendToolGuard {
        val response = client.search(query = query, rating = rating)
        val gif = response.data.firstOrNull() ?: return@suspendToolGuard """No GIF found for "$query"."""

        val url =
            gif.images.original.mp4
                ?: gif.images.original.url
                ?: return@suspendToolGuard "GIF found but has no usable URL."

        outbox.enqueue(BotOutput.Animation(url))

        gif.title
    }
}
