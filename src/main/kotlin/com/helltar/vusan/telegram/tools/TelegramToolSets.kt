package com.helltar.vusan.telegram.tools

import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.telegram.tools.sticker.StickerCatalog
import com.helltar.vusan.telegram.tools.sticker.StickerTools
import com.helltar.vusan.tools.PlatformToolSets
import org.telegram.telegrambots.meta.generics.TelegramClient

/**
 * What Telegram adds to a turn's tools: its own `file_id`, and the sticker sets this chat uses.
 *
 * [stickers] is absent where the deployment has no vision model, since the catalog only ever holds
 * stickers something has looked at — offering the model a sticker nobody described would be offering
 * it a random one.
 */
class TelegramToolSets(
    private val client: TelegramClient,
    private val stickers: StickerCatalog? = null
) : PlatformToolSets {

    override fun of(context: RequestContext, outbox: BotOutbox): List<ToolSet> {
        val chat = context.chat.capabilities

        return buildList {
            if (chat.documents) add(ChatFileTools(client, outbox))
            if (chat.stickersAndAnimations) stickers?.let { add(StickerTools(it, context, outbox)) }
        }
    }
}
