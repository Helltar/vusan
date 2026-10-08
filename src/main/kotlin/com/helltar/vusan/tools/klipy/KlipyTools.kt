package com.helltar.vusan.tools.klipy

import com.helltar.vusan.tools.LLMDescription
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.ChatCapabilities
import com.helltar.vusan.tools.files.FileDownloadClient
import com.helltar.vusan.tools.files.FileDownloadResult
import com.helltar.vusan.tools.files.MAX_DOWNLOAD_BYTES
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

/**
 * GIFs, meme pictures and short clips from KLIPY, found in one call and sent in another, so the model
 * reads what a search brought back before anything is queued and can turn all of it down.
 *
 * A tool set lives for one turn, and so do the candidates: [sendGif] takes only an id a search of
 * this turn returned, and takes each of them once. Candidates are listed in the order the provider
 * ranked them, and a file goes out under the address the provider gave for it.
 */
@Suppress("unused")
class KlipyTools(
    private val client: KlipyClient,
    private val downloads: FileDownloadClient,
    private val outbox: BotOutbox,
) : ToolSet {

    private val candidates = ConcurrentHashMap<String, Candidate>()
    private val sent = ConcurrentHashMap.newKeySet<String>()

    @Tool
    @LLMDescription(KlipyToolDescriptions.SEARCH_GIFS)
    suspend fun searchGifs(
        @LLMDescription(KlipyToolDescriptions.QUERY)
        query: String,
        @LLMDescription(KlipyToolDescriptions.KIND)
        kind: String = "gif",
    ): String = suspendToolGuard {
        val wanted = kindOf(kind)
        val words = query.requireToolText("query", MAX_QUERY_CHARS)

        require(wanted.deliverableIn(outbox.capabilities)) { "this chat does not accept a ${wanted.value}, search another kind" }

        val found =
            client.search(wanted, words, MAX_CANDIDATES)
                .mapNotNull { item -> item.mediaUrl(wanted)?.takeIf { item.slug.isNotBlank() }?.let { item to it } }

        if (found.isEmpty()) return@suspendToolGuard """No ${wanted.value} found for "$words"."""

        found.forEach { (item, url) -> candidates[item.slug] = Candidate(wanted, url, words) }

        val lines =
            found.joinToString("\n") { (item, _) ->
                val title = item.title.collapseWhitespaceAndCap(MAX_TITLE_CHARS) ?: "untitled"
                val note = if (item.slug in sent) " (already sent in this turn)" else ""
                "${item.slug}: $title$note"
            }

        xmlBlock("gif_candidates", lines) + "\nNothing was sent. Call `sendGif` with the id of the one that fits, or search again."
    }

    @Tool
    @LLMDescription(KlipyToolDescriptions.SEND_GIF)
    suspend fun sendGif(
        @LLMDescription(KlipyToolDescriptions.ID)
        id: String,
    ): String = suspendToolGuard {
        val key = id.trim()
        val candidate = requireNotNull(candidates[key]) { "unknown id [$key]: pass an id that `searchGifs` returned in this turn" }

        require(sent.add(key)) { "this one is already sent in this turn" }

        val queued =
            runCatching { outbox.enqueue(candidate.output()) }
                .onFailure { sent.remove(key) }
                .getOrThrow()

        if (!queued) {
            sent.remove(key)
            error("this chat does not accept a ${candidate.kind.value}, nothing was sent")
        }

        reportShare(key, candidate)

        "The ${candidate.kind.value} is queued and will be sent with your reply."
    }

    private fun kindOf(value: String): KlipyKind =
        requireNotNull(KlipyKind.entries.firstOrNull { it.value == value.trim().lowercase() }) {
            "unknown kind [$value]: use ${KlipyKind.entries.joinToString(", ") { "`${it.value}`" }}"
        }

    // a GIF goes out by its address; a picture and a video are kinds the outbox takes only as bytes.
    private suspend fun Candidate.output(): BotOutput =
        when (kind) {
            KlipyKind.GIF -> BotOutput.Animation(url)
            KlipyKind.MEME -> downloaded(MAX_MEME_BYTES).let { BotOutput.Photo(it.bytes, it.filename) }
            KlipyKind.CLIP -> downloaded(MAX_DOWNLOAD_BYTES).let { BotOutput.Video(it.bytes, it.filename) }
        }

    private suspend fun Candidate.downloaded(maxBytes: Long): FileDownloadResult.Success =
        when (val result = downloads.download(url, maxBytes = maxBytes)) {
            is FileDownloadResult.Success -> result
            is FileDownloadResult.TooLarge -> error("this ${kind.value} is too large to send, pick another")
        }

    // the send is already queued, so a report that fails costs the provider a count and nobody a reply.
    private suspend fun reportShare(slug: String, candidate: Candidate) {
        runCatching { client.reportShare(candidate.kind, slug, candidate.query) }
            .onFailure {
                it.rethrowIfCancellation()
                log.warn { "could not report a sent ${candidate.kind.value} to KLIPY: ${it.message}" }
            }
    }

    private class Candidate(val kind: KlipyKind, val url: String, val query: String)

    private companion object {
        val log = KotlinLogging.logger {}

        // the fewest a search may ask for
        const val MAX_CANDIDATES = 8
        const val MAX_QUERY_CHARS = 200
        const val MAX_TITLE_CHARS = 120
        const val MAX_MEME_BYTES = 10L * 1024 * 1024
    }
}

/** Whether a chat takes what an item of this kind is sent as. */
internal fun KlipyKind.deliverableIn(chat: ChatCapabilities): Boolean =
    when (this) {
        KlipyKind.GIF -> chat.stickersAndAnimations
        KlipyKind.MEME -> chat.photos
        KlipyKind.CLIP -> chat.videos
    }
