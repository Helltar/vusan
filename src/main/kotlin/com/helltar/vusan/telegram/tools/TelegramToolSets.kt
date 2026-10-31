package com.helltar.vusan.telegram.tools

import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.telegram.tools.sticker.StickerCatalog
import com.helltar.vusan.telegram.tools.sticker.StickerTools
import com.helltar.vusan.tools.PlatformToolSets
import org.telegram.telegrambots.meta.generics.TelegramClient

/**
 * What telegram adds to a turn's tools: its own `file_id`, and the sticker sets this chat uses. [stickers] is
 * absent without a vision model, since the catalog only holds described stickers and an undescribed one would
 * be a random pick.
 */
class TelegramToolSets(
    private val client: TelegramClient,
    private val stickers: StickerCatalog? = null,
) : PlatformToolSets {

    override fun of(context: RequestContext, outbox: BotOutbox): List<ToolSet> {
        val chat = context.chat.capabilities

        return buildList {
            // whatever the chat accepts: `send=false` fetches a file for another tool, and a send into a chat
            // refusing documents is answered before anything is fetched
            add(ChatFileTools(client, outbox))
            if (chat.stickersAndAnimations) stickers?.let { add(StickerTools(it, context, outbox)) }
        }
    }
}
