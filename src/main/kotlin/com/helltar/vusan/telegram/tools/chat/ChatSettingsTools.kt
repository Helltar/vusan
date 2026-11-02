package com.helltar.vusan.telegram.tools.chat

import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.telegram.ChatProfiles
import com.helltar.vusan.telegram.api
import com.helltar.vusan.telegram.delivery.asInputFile
import com.helltar.vusan.telegram.telegramChatId
import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard
import org.telegram.telegrambots.meta.api.methods.groupadministration.SetChatDescription
import org.telegram.telegrambots.meta.api.methods.groupadministration.SetChatPhoto
import org.telegram.telegrambots.meta.api.methods.groupadministration.SetChatTitle
import org.telegram.telegrambots.meta.generics.TelegramClient

internal const val MAX_TITLE_CHARS = 128
internal const val MAX_DESCRIPTION_CHARS = 255

/**
 * Changes a group's photo, title and description. Offered only where the bot is an admin with the right to,
 * and each call also needs the person asking to hold that right; see [requireSenderHolds].
 */
class ChatSettingsTools(
    private val telegram: TelegramClient,
    private val profiles: ChatProfiles,
    private val context: RequestContext,
) : ToolSet {

    private val chatId: Long
        get() = context.chatRef.telegramChatId

    @Tool(ChatSettingsToolDescriptions.SET_CHAT_PHOTO)
    suspend fun setChatPhoto(
        @Arg(ChatSettingsToolDescriptions.PHOTO_IMAGE)
        image: AttachedFile? = null,
    ): String = suspendToolGuard {
        val photo =
            requireNotNull(image ?: context.attachedFiles.firstOrNull { it.kind == AttachedFileKind.IMAGE }) {
                "no picture is attached in this turn; name one by label, or ask the user to send or reply to one"
            }

        require(photo.kind == AttachedFileKind.IMAGE) { "`${photo.name}` is not a picture" }
        telegram.requireSenderHolds(context, ChatRight.CHANGE_INFO)

        val bytes = photo.loadBytes()
        telegram.api { executeAsync(SetChatPhoto.builder().chatId(chatId).photo(bytes.asInputFile(photo.name)).build()) }

        "The group's photo is now `${photo.name}`."
    }

    @Tool(ChatSettingsToolDescriptions.SET_CHAT_TITLE)
    suspend fun setChatTitle(
        @Arg(ChatSettingsToolDescriptions.TITLE)
        title: String,
    ): String = suspendToolGuard {
        val newTitle = title.requireToolText("Title", MAX_TITLE_CHARS)
        telegram.requireSenderHolds(context, ChatRight.CHANGE_INFO)

        telegram.api { executeAsync(SetChatTitle.builder().chatId(chatId).title(newTitle).build()) }

        "The group is now called \"$newTitle\"."
    }

    @Tool(ChatSettingsToolDescriptions.SET_CHAT_DESCRIPTION)
    suspend fun setChatDescription(
        @Arg(ChatSettingsToolDescriptions.DESCRIPTION)
        description: String = "",
    ): String = suspendToolGuard {
        val newDescription = description.trim()
        require(newDescription.length <= MAX_DESCRIPTION_CHARS) { "Description must be at most $MAX_DESCRIPTION_CHARS characters" }
        telegram.requireSenderHolds(context, ChatRight.CHANGE_INFO)

        telegram.api { executeAsync(SetChatDescription.builder().chatId(chatId).description(newDescription).build()) }
        // the description rides in the prompt from the cached profile
        profiles.forget(chatId)

        if (newDescription.isEmpty()) "The group's description is cleared." else "The group's description is replaced."
    }
}
