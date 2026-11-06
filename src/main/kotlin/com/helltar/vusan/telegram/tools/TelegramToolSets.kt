package com.helltar.vusan.telegram.tools

import com.helltar.vusan.delivery.OutputDelivery
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.telegram.ChatProfiles
import com.helltar.vusan.telegram.tools.chat.ChatPhotoTools
import com.helltar.vusan.telegram.tools.chat.ChatSettingsTools
import com.helltar.vusan.telegram.tools.chat.ForumTopicTools
import com.helltar.vusan.telegram.tools.sticker.StickerCatalog
import com.helltar.vusan.telegram.tools.sticker.StickerTools
import com.helltar.vusan.tools.PlatformToolSet
import com.helltar.vusan.tools.PlatformToolSets
import com.helltar.vusan.tools.ToolGroup
import org.telegram.telegrambots.meta.generics.TelegramClient

/**
 * What telegram adds to a turn's tools: its own `file_id`, the sticker sets this chat uses, a group's photo, and
 * the group settings and forum topics the bot is an admin over. [stickers] is absent without a vision model,
 * since the catalog only holds described stickers and an undescribed one would be a random pick.
 */
class TelegramToolSets(
    private val client: TelegramClient,
    private val delivery: OutputDelivery,
    private val profiles: ChatProfiles,
    private val stickers: StickerCatalog? = null,
) : PlatformToolSets {

    override fun of(context: RequestContext, outbox: BotOutbox): List<PlatformToolSet> {
        val chat = context.chat.capabilities

        return buildList {
            // whatever the chat accepts: `send=false` fetches a file for another tool, and a send into a chat
            // refusing documents is answered before anything is fetched
            add(PlatformToolSet(ChatFileTools(client, outbox)))
            if (chat.stickersAndAnimations) stickers?.let { add(PlatformToolSet(StickerTools(it, context, outbox))) }
            if (!context.chat.isPrivate) add(PlatformToolSet(ChatPhotoTools(client, context, outbox), ToolGroup.CHAT_PHOTO))
            if (chat.chatInfo) add(PlatformToolSet(ChatSettingsTools(client, profiles, context), ToolGroup.CHAT_SETTINGS))
            if (chat.topics) add(PlatformToolSet(ForumTopicTools(client, delivery, context), ToolGroup.FORUM_TOPICS))
        }
    }
}
