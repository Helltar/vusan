package com.helltar.vusan.tools.sticker

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.tools.suspendToolGuard

@Suppress("unused")
class StickerTools(
    private val catalog: StickerCatalog,
    private val outbox: BotOutbox
) : ToolSet {

    @Tool
    @LLMDescription(StickerToolDescriptions.SEND_STICKER)
    suspend fun sendSticker(
        @LLMDescription(StickerToolDescriptions.STICKER_ID)
        id: Long
    ): String = suspendToolGuard {
        val fileId =
            catalog.fileIdFor(id)
                ?: return@suspendToolGuard "No sticker id=$id in the catalog. Use an id listed in `<sticker_catalog>`."

        outbox.enqueue(BotOutput.Sticker(fileId, catalogId = id))

        "Sticker $id queued."
    }
}
