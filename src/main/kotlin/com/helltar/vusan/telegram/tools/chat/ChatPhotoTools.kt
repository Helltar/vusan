package com.helltar.vusan.telegram.tools.chat

import com.helltar.vusan.common.asFileSize
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.telegram.api
import com.helltar.vusan.telegram.downloadFileBytes
import com.helltar.vusan.telegram.telegramChatId
import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.tools.keepOnShelf
import com.helltar.vusan.tools.keptNotSent
import com.helltar.vusan.tools.refusedByChat
import com.helltar.vusan.tools.suspendToolGuard
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat
import org.telegram.telegrambots.meta.generics.TelegramClient

/**
 * Reads a group's current photo, which any member may see, so unlike [ChatSettingsTools] it needs no admin
 * right. The photo is read fresh on every call: its `file_id` stops working once the photo changes.
 */
class ChatPhotoTools(
    private val telegram: TelegramClient,
    private val context: RequestContext,
    private val outbox: BotOutbox,
) : ToolSet {

    @Tool(ChatPhotoToolDescriptions.GET_CHAT_PHOTO)
    suspend fun getChatPhoto(
        @Arg(ChatPhotoToolDescriptions.SEND)
        send: Boolean = true,
    ): String = suspendToolGuard {
        if (send && !outbox.capabilities.photos)
            return@suspendToolGuard refusedByChat("photos")

        val chatId = context.chatRef.telegramChatId
        val photo =
            telegram.api { executeAsync(GetChat.builder().chatId(chatId).build()) }.photo
                ?: return@suspendToolGuard "This group has no photo."

        val bytes = telegram.downloadFileBytes(photo.bigFileId)
        val label = keepOnShelf(GROUP_PHOTO_NAME, bytes)
        val what = "The group's photo (${bytes.size.toLong().asFileSize()})"

        if (!send) return@suspendToolGuard keptNotSent(what, label)

        outbox.enqueue(BotOutput.Photo(bytes = bytes, filename = GROUP_PHOTO_NAME))

        "$what will be sent."
    }

    private companion object {
        const val GROUP_PHOTO_NAME = "group_photo.jpg"
    }
}
