package com.helltar.vusan.tools.sticker

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard

@Suppress("unused")
class StickerTools(
    private val catalog: StickerCatalog,
    private val context: RequestContext,
    private val outbox: BotOutbox
) : ToolSet {

    private companion object {
        const val MAX_QUERY_CHARS = 120
        const val DEFAULT_SEARCH_RESULTS = 8
        const val MAX_SEARCH_RESULTS = 12
    }

    @Tool
    @LLMDescription(StickerToolDescriptions.SEARCH_STICKERS)
    suspend fun searchStickers(
        @LLMDescription(StickerToolDescriptions.SEARCH_QUERY)
        query: String,
        @LLMDescription(StickerToolDescriptions.MAX_RESULTS)
        maxResults: Int = DEFAULT_SEARCH_RESULTS
    ): String = suspendToolGuard {
        val cleanedQuery = query.requireToolText("query", MAX_QUERY_CHARS)
        val matches = catalog.search(context.chatId, cleanedQuery, maxResults.coerceIn(1, MAX_SEARCH_RESULTS))

        if (matches.isEmpty()) {
            return@suspendToolGuard "No stickers matched query=[$cleanedQuery]. Try one short English synonym, " +
                    "then send no sticker if that also misses."
        }

        "Use one of these sticker ids with `sendSticker` if it genuinely fits; otherwise send no sticker:\n" +
                matches.joinToString("\n", transform = StickerEntry::catalogLine)
    }

    @Tool
    @LLMDescription(StickerToolDescriptions.SEND_STICKER)
    suspend fun sendSticker(
        @LLMDescription(StickerToolDescriptions.STICKER_ID)
        id: Long
    ): String = suspendToolGuard {
        val fileId =
            catalog.fileIdFor(context.chatId, id)
                ?: return@suspendToolGuard "No sticker id=$id in this chat's catalog. Use an id listed in " +
                        "`<sticker_catalog>` or returned by `searchStickers`."

        outbox.enqueue(BotOutput.Sticker(fileId, catalogId = id))

        "Sticker $id queued."
    }
}
