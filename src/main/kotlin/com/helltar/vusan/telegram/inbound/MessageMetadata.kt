package com.helltar.vusan.telegram.inbound

import com.helltar.vusan.agent.MessageContext
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.telegram.ChatProfile
import org.telegram.telegrambots.meta.api.objects.*
import org.telegram.telegrambots.meta.api.objects.chat.Chat
import org.telegram.telegrambots.meta.api.objects.games.Animation
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.messageorigin.*
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize
import org.telegram.telegrambots.meta.api.objects.stickers.Sticker

private const val MAX_METADATA_VALUE_CHARS = 500

// both mirror the varchar widths in GroupLogTable.
private const val MAX_DESCRIPTOR_CHARS = 200
private const val MAX_FORWARD_LABEL_CHARS = 128

private const val SECONDS_PER_MINUTE = 60
private const val MINUTES_PER_HOUR = 60

internal val Message.chatIdLong: Long
    get() = chat.id

internal val Message.messageIdLong: Long
    get() = messageId.toLong()

internal val Message.canLoadChatDescription: Boolean
    get() = chat.isGroupChat || chat.isSuperGroupChat || chat.isChannelChat

internal val Message.isPrivateChat: Boolean
    get() = chat.isUserChat

internal fun Message.senderIdOrNull(): Long? = from?.id

internal fun Message.senderDisplayNameOrNull(): String? =
    from?.let { displayName(it.firstName, it.lastName) }

internal fun Message.senderUsernameOrNull(): String? = from?.userName

internal fun Message.senderLanguageCodeOrNull(): String? = from?.languageCode

internal val Message.language: Language
    get() = Language.fromCode(senderLanguageCodeOrNull())

internal fun Message.textSnippetOrNull(): String? =
    text ?: caption ?: richMessage?.toRichMarkdown()?.takeIf { it.isNotBlank() }

internal fun Message.replyAuthorIdOrNull(): Long? = replyToMessage?.from?.id

internal fun Message.replyToMessageIdOrNull(): Long? = replyToMessage?.messageId?.toLong()

internal fun Message.toMessageContext(profile: ChatProfile): MessageContext? {
    val sender = from ?: return null
    return toMessageContext(sender, profile)
}

internal fun Message.toMessageContext(sender: User, profile: ChatProfile): MessageContext =
    MessageContext(
        chatId = chatIdLong,
        chatType = promptChatType(),
        isPrivate = isPrivateChat,
        chatTitle = chat.titleOrDisplayName(),
        chatUsername = chat.userName,
        chatDescription = profile.description,
        userId = sender.id,
        userDisplayName = displayName(sender.firstName, sender.lastName),
        userUsername = sender.userName,
        userLanguageCode = sender.languageCode,
        chatCapabilities = profile.capabilities
    )

// the bot api models chat flavors as flags on `Chat` and `Message` rather than distinct types,
// so the prompt label is assembled from those flags.
internal fun Message.promptChatType(): String =
    when {
        businessConnectionId != null -> "business"
        chat.isUserChat && chat.isForum == true -> "private_forum"
        chat.isUserChat -> "private"
        directMessagesTopic != null -> "channel_direct_messages"
        chat.isSuperGroupChat && chat.isForum == true -> "supergroup_forum"
        chat.isSuperGroupChat -> "supergroup"
        chat.isGroupChat -> "group"
        chat.isChannelChat -> "channel"
        else -> "unknown"
    }

internal fun Chat.titleOrDisplayName(): String? =
    title ?: displayName(firstName, lastName)

internal fun displayName(firstName: String?, lastName: String?): String? =
    listOfNotNull(firstName, lastName)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .takeIf { it.isNotBlank() }

internal fun Message.contentTypeName(): String =
    when {
        text != null -> "text"
        richMessage != null -> "rich message"
        poll != null -> "poll"
        contact != null -> "contact"
        location != null -> "location"
        venue != null -> "venue"
        dice != null -> "dice"
        story != null -> "story"
        else -> mediaAttachment().typeNameOrNull() ?: "unknown"
    }

internal fun Message.mediaMetadataLines(): List<String> = mediaAttachment().metadataLines()

/**
 * A short human label for content the chat log cannot carry as text — what a person would say the
 * message was. Deliberately not [mediaMetadataLines]: file ids describe nothing that happened in the
 * conversation and only cost tokens when the transcript is read back.
 */
internal fun Message.groupLogDescriptor(): String? =
    when {
        sticker != null -> listOfNotNull(sticker.emoji, sticker.setName).joinToString(" ")
        // animation is checked before document for the same reason as everywhere else: a gif carries both.
        animation != null -> animation.duration.asClock()
        !photo.isNullOrEmpty() -> null
        video != null -> video.duration?.asClock()
        videoNote != null -> videoNote.duration?.asClock()
        voice != null -> voice.duration?.asClock()
        audio != null -> audioDescriptor()
        document != null -> document.fileName
        poll != null -> poll.question
        venue != null -> venue.title
        else -> null
    }?.collapseWhitespaceAndCap(MAX_DESCRIPTOR_CHARS)

/**
 * Who a reposted message originally came from, or `null` when the message is not a forward. Telegram
 * models a repost as a [MessageOrigin] variant rather than a flag on the text, so without this a
 * forwarded channel post reads exactly as if the sender had typed it themselves.
 */
internal fun Message.forwardOriginLabel(): String? =
    when (val origin = forwardOrigin) {
        is MessageOriginChannel -> origin.chat?.titleOrDisplayName() ?: origin.chat?.userName
        is MessageOriginChat -> origin.senderChat?.titleOrDisplayName()
        is MessageOriginUser -> origin.senderUser?.let { displayName(it.firstName, it.lastName) ?: it.userName }
        is MessageOriginHiddenUser -> origin.senderUserName
        else -> null
    }?.collapseWhitespaceAndCap(MAX_FORWARD_LABEL_CHARS)

private fun Message.audioDescriptor(): String? =
    listOfNotNull(audio.performer, audio.title)
        .joinToString(" - ")
        .ifBlank { audio.duration?.asClock() }

private fun Int.asClock(): String {
    val minutes = this / SECONDS_PER_MINUTE
    val seconds = this % SECONDS_PER_MINUTE

    if (minutes < MINUTES_PER_HOUR) return "$minutes:${seconds.toString().padStart(2, '0')}"

    return "${minutes / MINUTES_PER_HOUR}:" +
            "${(minutes % MINUTES_PER_HOUR).toString().padStart(2, '0')}:" +
            seconds.toString().padStart(2, '0')
}

// external replies expose only the media descriptor of the quoted message, never its text.
internal fun ExternalReplyInfo.summaryTypeNameOrNull(): String? =
    mediaAttachment().typeNameOrNull() ?: story?.let { "story" } ?: poll?.let { "poll" }

internal fun ExternalReplyInfo.mediaMetadataLines(): List<String> = mediaAttachment().metadataLines()

internal fun List<PhotoSize>.biggestOrNull(): PhotoSize? =
    maxByOrNull { (it.width ?: 0) * (it.height ?: 0) }

internal fun Sticker.readableFormat(): String =
    when {
        isAnimated == true -> "animated"
        isVideo == true -> "video"
        else -> "static"
    }

internal fun describeIncomingSticker(sticker: Sticker): String =
    buildString {
        appendLine("User sent a Telegram sticker instead of text.")
        appendLine("Sticker emoji: ${sticker.emoji ?: "unknown"}.")
        appendLine("Sticker pack: ${sticker.setName ?: "unknown"}.")
        append("Sticker kind: ${sticker.readableFormat()} ${sticker.type ?: "regular"} sticker.")
    }

// the same media fields exist on both `Message` and `ExternalReplyInfo`; this descriptor lets one
// classification and one metadata builder serve both.
private class MediaAttachment(
    val sticker: Sticker?,
    val animation: Animation?,
    val photos: List<PhotoSize>?,
    val video: Video?,
    val videoNote: VideoNote?,
    val voice: Voice?,
    val audio: Audio?,
    val document: Document?
)

private fun Message.mediaAttachment(): MediaAttachment =
    MediaAttachment(sticker, animation, photo, video, videoNote, voice, audio, document)

private fun ExternalReplyInfo.mediaAttachment(): MediaAttachment =
    MediaAttachment(sticker, animation, photo, video, videoNote, voice, audio, document)

// animation is checked before photo/document because telegram sets both `animation` and
// `document` on gif messages.
private fun MediaAttachment.typeNameOrNull(): String? =
    when {
        sticker != null -> "sticker"
        animation != null -> "animation"
        !photos.isNullOrEmpty() -> "photo"
        video != null -> "video"
        videoNote != null -> "video note"
        voice != null -> "voice"
        audio != null -> "audio"
        document != null -> "document"
        else -> null
    }

private fun MediaAttachment.metadataLines(): List<String> =
    when {
        sticker != null -> sticker.metadataLines()

        animation != null -> animation.let {
            fileMetadataLines(
                it.fileId, it.fileUniqueId, it.fileSize,
                width = it.width, height = it.height, durationSeconds = it.duration,
                mimeType = it.mimeType, fileName = it.fileName
            )
        }

        !photos.isNullOrEmpty() -> photoMetadataLines(photos)

        video != null -> video.let {
            fileMetadataLines(
                it.fileId, it.fileUniqueId, it.fileSize,
                width = it.width, height = it.height, durationSeconds = it.duration,
                mimeType = it.mimeType, fileName = it.fileName
            )
        }

        videoNote != null -> videoNote.let {
            fileMetadataLines(it.fileId, it.fileUniqueId, it.fileSize?.toLong(), durationSeconds = it.duration)
        }

        voice != null -> voice.let {
            fileMetadataLines(it.fileId, it.fileUniqueId, it.fileSize, durationSeconds = it.duration, mimeType = it.mimeType)
        }

        audio != null -> audio.let {
            fileMetadataLines(
                it.fileId, it.fileUniqueId, it.fileSize,
                durationSeconds = it.duration, mimeType = it.mimeType, fileName = it.fileName
            )
        }

        document != null -> document.let {
            fileMetadataLines(it.fileId, it.fileUniqueId, it.fileSize, mimeType = it.mimeType, fileName = it.fileName)
        }

        else -> emptyList()
    }

private fun photoMetadataLines(photos: List<PhotoSize>): List<String> {
    val biggest = photos.biggestOrNull() ?: return emptyList()

    return buildList {
        addMetadata("file_id", biggest.fileId)
        addMetadata("file_unique_id", biggest.fileUniqueId)
        biggest.fileSize?.let { addMetadata("file_size_bytes", it.toString()) }
        addMetadata("photo_sizes_count", photos.size.toString())
        addMetadata("biggest_photo_width", biggest.width.toString())
        addMetadata("biggest_photo_height", biggest.height.toString())
    }
}

private fun Sticker.metadataLines(): List<String> =
    buildList {
        addMetadata("file_id", fileId)
        addMetadata("file_unique_id", fileUniqueId)
        fileSize?.let { addMetadata("file_size_bytes", it.toString()) }
        width?.let { addMetadata("width", it.toString()) }
        height?.let { addMetadata("height", it.toString()) }
        addMetadata("sticker_type", type ?: "regular")
        addMetadata("sticker_format", readableFormat())
        emoji?.let { addMetadata("sticker_emoji", it) }
        setName?.let { addMetadata("sticker_set_name", it) }
    }

private fun fileMetadataLines(
    fileId: String,
    fileUniqueId: String,
    fileSizeBytes: Long?,
    width: Int? = null,
    height: Int? = null,
    durationSeconds: Int? = null,
    mimeType: String? = null,
    fileName: String? = null
): List<String> =
    buildList {
        addMetadata("file_id", fileId)
        addMetadata("file_unique_id", fileUniqueId)
        fileSizeBytes?.let { addMetadata("file_size_bytes", it.toString()) }
        width?.let { addMetadata("width", it.toString()) }
        height?.let { addMetadata("height", it.toString()) }
        durationSeconds?.let { addMetadata("duration_seconds", it.toString()) }
        mimeType?.let { addMetadata("mime_type", it) }
        fileName?.let { addMetadata("file_name", it) }
    }

private fun MutableList<String>.addMetadata(key: String, value: String) {
    value.collapseWhitespaceAndCap(MAX_METADATA_VALUE_CHARS)?.let { add("$key: $it") }
}
