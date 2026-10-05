package com.helltar.vusan.tools.giphy

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard
import java.util.concurrent.ConcurrentHashMap

/**
 * Finding a GIF and sending one are two calls, so the model reads what a search brought back before
 * anything is queued and can turn all of it down.
 *
 * A tool set lives for one turn, and so do the candidates: [sendGif] takes only an id a search of
 * this turn returned, and takes each of them once.
 */
@Suppress("unused")
class GiphyTools(private val client: GiphyClient, private val outbox: BotOutbox) : ToolSet {

    private val candidates = ConcurrentHashMap<String, String>()
    private val sent = ConcurrentHashMap.newKeySet<String>()

    @Tool
    @LLMDescription(GiphyToolDescriptions.SEARCH_GIFS)
    suspend fun searchGifs(
        @LLMDescription(GiphyToolDescriptions.QUERY)
        query: String,
        @LLMDescription(GiphyToolDescriptions.RATING)
        rating: String = "g",
    ): String = suspendToolGuard {
        val found =
            client.search(query = query.requireToolText("query", MAX_QUERY_CHARS), limit = MAX_CANDIDATES, rating = rating)
                .data
                .mapNotNull { gif -> (gif.images.original.mp4 ?: gif.images.original.url)?.let { gif to it } }

        if (found.isEmpty()) return@suspendToolGuard """No GIF found for "$query"."""

        found.forEach { (gif, url) -> candidates[gif.id] = url }

        val lines =
            found.joinToString("\n") { (gif, _) ->
                val title = gif.title.collapseWhitespaceAndCap(MAX_TITLE_CHARS) ?: "untitled"
                val note = if (gif.id in sent) " (already sent in this turn)" else ""
                "${gif.id}: $title$note"
            }

        xmlBlock("gif_candidates", lines) + "\nNothing was sent. Call `sendGif` with the id of the one that fits, or search again."
    }

    @Tool
    @LLMDescription(GiphyToolDescriptions.SEND_GIF)
    suspend fun sendGif(
        @LLMDescription(GiphyToolDescriptions.ID)
        id: String,
    ): String = suspendToolGuard {
        val key = id.trim()
        val url = requireNotNull(candidates[key]) { "unknown GIF id [$key]: pass an id that `searchGifs` returned in this turn" }

        require(sent.add(key)) { "this GIF is already sent in this turn" }

        if (!outbox.enqueue(BotOutput.Animation(url))) {
            sent.remove(key)
            error("this chat does not accept GIFs, nothing was sent")
        }

        "The GIF is queued and will be sent with your reply."
    }

    private companion object {
        const val MAX_CANDIDATES = 8
        const val MAX_QUERY_CHARS = 200
        const val MAX_TITLE_CHARS = 120
    }
}
