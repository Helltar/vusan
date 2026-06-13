package com.helltar.vusan.telegram

import com.helltar.vusan.agent.MessageContext
import com.helltar.vusan.common.collapseWhitespaceAndCap
import dev.inmo.tgbotapi.types.ReplyInfo
import dev.inmo.tgbotapi.types.StickerType
import dev.inmo.tgbotapi.types.abstracts.WithOptionalLanguageCode
import dev.inmo.tgbotapi.types.chat.BusinessChat
import dev.inmo.tgbotapi.types.chat.ChannelChat
import dev.inmo.tgbotapi.types.chat.ChannelDirectMessagesChat
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.types.chat.GroupChat
import dev.inmo.tgbotapi.types.chat.PrivateChat
import dev.inmo.tgbotapi.types.chat.PrivateForumChat
import dev.inmo.tgbotapi.types.chat.PublicChat
import dev.inmo.tgbotapi.types.chat.SupergroupChat
import dev.inmo.tgbotapi.types.chat.SupergroupForumChat
import dev.inmo.tgbotapi.types.chat.UsernameChat
import dev.inmo.tgbotapi.types.files.CustomNamedMediaFile
import dev.inmo.tgbotapi.types.files.MimedMediaFile
import dev.inmo.tgbotapi.types.files.PhotoFile
import dev.inmo.tgbotapi.types.files.PlayableMediaFile
import dev.inmo.tgbotapi.types.files.SizedMediaFile
import dev.inmo.tgbotapi.types.files.Sticker
import dev.inmo.tgbotapi.types.files.TelegramMediaFile
import dev.inmo.tgbotapi.types.message.abstracts.ChatContentMessage
import dev.inmo.tgbotapi.types.message.abstracts.OptionallyFromUserMessage
import dev.inmo.tgbotapi.types.message.content.TextedContent

private const val MAX_METADATA_VALUE_CHARS = 500

internal val ChatContentMessage<*>.chatIdLong: Long
    get() = chat.id.chatId.long

internal val ChatContentMessage<*>.messageIdLong: Long
    get() = messageId.long

internal val ChatContentMessage<*>.canLoadChatDescription: Boolean
    get() = chat is PublicChat

internal val ChatContentMessage<*>.isPrivateChat: Boolean
    get() = chat is PrivateChat

internal fun ChatContentMessage<*>.senderIdOrNull(): Long? =
    (this as? OptionallyFromUserMessage)?.from?.id?.chatId?.long

internal fun ChatContentMessage<*>.senderDisplayNameOrNull(): String? =
    (this as? OptionallyFromUserMessage)?.from?.let { displayName(it.firstName, it.lastName) }

internal fun ChatContentMessage<*>.senderUsernameOrNull(): String? =
    (this as? OptionallyFromUserMessage)?.from?.username?.full

internal fun ChatContentMessage<*>.senderLanguageCodeOrNull(): String? =
    ((this as? OptionallyFromUserMessage)?.from as? WithOptionalLanguageCode)?.languageCode

internal fun ChatContentMessage<*>.textSnippetOrNull(): String? =
    (content as? TextedContent)?.text

internal fun ChatContentMessage<*>.replyAuthorIdOrNull(): Long? =
    ((replyInfo as? ReplyInfo.Internal)?.message as? OptionallyFromUserMessage)?.from?.id?.chatId?.long

internal fun ChatContentMessage<*>.replyToMessageIdOrNull(): Long? =
    (replyInfo as? ReplyInfo.Internal)?.message?.metaInfo?.messageId?.long

internal fun ChatContentMessage<*>.toMessageContext(chatDescription: String?): MessageContext? {
    val senderId = senderIdOrNull() ?: return null

    return MessageContext(
        chatId = chatIdLong,
        chatType = chat.promptType(),
        isPrivate = isPrivateChat,
        chatTitle = chat.titleOrDisplayName(),
        chatUsername = (chat as? UsernameChat)?.username?.full,
        chatDescription = chatDescription,
        userId = senderId,
        userDisplayName = senderDisplayNameOrNull(),
        userUsername = senderUsernameOrNull()
    )
}

internal fun Chat.promptType(): String =
    when (this) {
        is PrivateForumChat -> "private_forum"
        is PrivateChat -> "private"
        is ChannelDirectMessagesChat -> "channel_direct_messages"
        is SupergroupForumChat -> "supergroup_forum"
        is SupergroupChat -> "supergroup"
        is GroupChat -> "group"
        is ChannelChat -> "channel"
        is BusinessChat -> "business"
        else -> "unknown"
    }

internal fun Chat.titleOrDisplayName(): String? =
    when (this) {
        is PublicChat -> title
        is PrivateChat -> displayName(firstName, lastName)
        is BusinessChat -> displayName(original.firstName, original.lastName)
        else -> null
    }

internal fun displayName(firstName: String, lastName: String): String? =
    listOf(firstName, lastName)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .takeIf { it.isNotBlank() }

internal fun Any.contentTypeName(): String =
    this::class.simpleName
        ?.removeSuffix("Content")
        ?.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        ?.lowercase()
        ?: "unknown"

internal fun TelegramMediaFile.toMetadataLines(): List<String> =
    buildList {
        addMetadata("file_id", fileId.fileId)
        addMetadata("file_unique_id", fileUniqueId.string)

        fileSize?.let { addMetadata("file_size_bytes", it.bytes.toString()) }

        if (this@toMetadataLines is PhotoFile) {
            addMetadata("photo_sizes_count", size.toString())
            addMetadata("biggest_photo_width", biggest.width.toString())
            addMetadata("biggest_photo_height", biggest.height.toString())
        }

        (this@toMetadataLines as? SizedMediaFile)?.let {
            addMetadata("width", it.width.toString())
            addMetadata("height", it.height.toString())
        }

        (this@toMetadataLines as? PlayableMediaFile)?.duration?.let {
            addMetadata("duration_seconds", it.toString())
        }

        (this@toMetadataLines as? MimedMediaFile)?.mimeType?.let {
            addMetadata("mime_type", it.toString())
        }

        (this@toMetadataLines as? CustomNamedMediaFile)?.fileName?.let {
            addMetadata("file_name", it)
        }

        (this@toMetadataLines as? Sticker)?.let {
            addMetadata("sticker_type", it.type.readableName())
            addMetadata("sticker_format", it.readableFormat())
            it.emoji?.let { emoji -> addMetadata("sticker_emoji", emoji) }
            it.stickerSetName?.let { setName -> addMetadata("sticker_set_name", setName.string) }
        }
    }

internal fun StickerType.readableName(): String =
    when (this) {
        StickerType.Regular -> "regular"
        StickerType.Mask -> "mask"
        StickerType.CustomEmoji -> "custom_emoji"
        is StickerType.Unknown -> type
    }

internal fun Sticker.readableFormat(): String =
    when {
        isAnimated -> "animated"
        isVideo -> "video"
        else -> "static"
    }

internal fun describeIncomingSticker(sticker: Sticker): String =
    buildString {
        appendLine("User sent a Telegram sticker instead of text.")
        appendLine("Sticker emoji: ${sticker.emoji ?: "unknown"}.")
        appendLine("Sticker pack: ${sticker.stickerSetName?.string ?: "unknown"}.")
        append("Sticker kind: ${sticker.readableFormat()} ${sticker.type.readableName()} sticker.")
    }

private fun MutableList<String>.addMetadata(key: String, value: String) {
    value.collapseWhitespaceAndCap(MAX_METADATA_VALUE_CHARS)?.let { add("$key: $it") }
}
