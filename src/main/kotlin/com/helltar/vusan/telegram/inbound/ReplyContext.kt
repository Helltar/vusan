package com.helltar.vusan.telegram.inbound

import com.helltar.vusan.agent.RepliedMessageSummary
import com.helltar.vusan.agent.neutralizePromptBlocks
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.limitTo
import com.helltar.vusan.common.sanitizeFilename
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import com.helltar.vusan.telegram.downloadFileBytes
import org.telegram.telegrambots.meta.api.objects.Document
import org.telegram.telegrambots.meta.api.objects.ExternalReplyInfo
import org.telegram.telegrambots.meta.api.objects.Video
import org.telegram.telegrambots.meta.api.objects.VideoNote
import org.telegram.telegrambots.meta.api.objects.games.Animation
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize
import org.telegram.telegrambots.meta.generics.TelegramClient

private const val MAX_REPLIED_TEXT_CHARS = 4096
private const val MAX_QUOTED_FRAGMENT_CHARS = 1024

// a first name, a last name and a username, with room to spare for telegram's own limits.
private const val MAX_AUTHOR_CHARS = 160

internal fun isReplyToOtherUser(replyAuthorId: Long?, botUserId: Long): Boolean =
    replyAuthorId != botUserId

// the part of the replied message the sender selected before answering — the whole point of their
// question ("what is this?" against one term inside a long answer). it also carries its own weight
// when the reply goes to the bot: that message is already in the history, but which piece of it the
// user pointed at is not, and without this the turn reads as a question about the whole thing.
internal fun Message.quotedFragmentOrNull(): String? =
    quote?.text?.collapseWhitespaceAndCap(MAX_QUOTED_FRAGMENT_CHARS)?.takeIf { it.isNotBlank() }

internal suspend fun Message.replySummaryOrNull(
    client: TelegramClient,
    voiceTranscriber: VoiceTranscriber?,
    botUserId: Long,
): RepliedMessageSummary? {
    val base = toReplySummary(botUserId) ?: return null
    val transcript = transcribeRepliedAudioOrNull(client, voiceTranscriber)

    return transcript?.let { base.copy(transcript = it) } ?: base
}

private suspend fun Message.transcribeRepliedAudioOrNull(
    client: TelegramClient,
    voiceTranscriber: VoiceTranscriber?,
): String? {
    if (voiceTranscriber == null) return null

    val replied = replyToMessage ?: return null
    val audioInput = replied.voice?.toAudioInput() ?: replied.audio?.toAudioInput() ?: return null

    return when (val result = voiceTranscriber.transcribe(client, audioInput)) {
        is VoiceTranscriptionResult.Success -> result.text
        else -> null
    }
}

internal fun Message.repliedAttachedFileOrNull(client: TelegramClient): AttachedFile? =
    replyToMessage?.toAttachedFileOrNull(client)

// gif messages carry both `animation` and `document`, so the animation is matched first and the
// document copy of the same file never turns into a second attachment.
internal fun Message.toAttachedFileOrNull(client: TelegramClient): AttachedFile? =
    photo?.biggestOrNull()?.toAttachedFile(client, caption)
        ?: video?.toAttachedFile(client, caption)
        ?: animation?.toAttachedFile(client, caption)
        ?: videoNote?.toAttachedFile(client, caption)
        ?: document?.toAttachedFile(client, caption)

private fun PhotoSize.toAttachedFile(client: TelegramClient, caption: String?): AttachedFile =
    AttachedFile(
        name = "photo.jpg",
        fileSizeBytes = fileSize?.toLong(),
        mimeType = "image/jpeg",
        kind = AttachedFileKind.IMAGE,
        caption = caption,
        loadBytes = { client.downloadFileBytes(fileId) },
    )

private fun Video.toAttachedFile(client: TelegramClient, caption: String?): AttachedFile =
    videoAttachedFile(
        client = client,
        caption = caption,
        fileId = fileId,
        name = fileName.orVideoName(fileUniqueId),
        fileSizeBytes = fileSize,
        mimeType = mimeType,
        durationSeconds = duration,
        thumbnailFileId = thumbnail?.fileId,
    )

private fun Animation.toAttachedFile(client: TelegramClient, caption: String?): AttachedFile =
    videoAttachedFile(
        client = client,
        caption = caption,
        fileId = fileId,
        name = fileName.orVideoName(fileUniqueId),
        fileSizeBytes = fileSize,
        mimeType = mimeType,
        durationSeconds = duration,
        thumbnailFileId = thumbnail?.fileId,
        isAnimation = true,
    )

private fun VideoNote.toAttachedFile(client: TelegramClient, caption: String?): AttachedFile =
    videoAttachedFile(
        client = client,
        caption = caption,
        fileId = fileId,
        name = "video-note-$fileUniqueId.mp4",
        fileSizeBytes = fileSize?.toLong(),
        mimeType = null,
        durationSeconds = duration,
        thumbnailFileId = thumbnail?.fileId,
    )

private fun Document.toAttachedFile(client: TelegramClient, caption: String?): AttachedFile {
    val safeName = (fileName ?: "file").sanitizeFilename().ifBlank { "file" }
    val kind = documentKind(mimeType, safeName)

    if (kind == AttachedFileKind.VIDEO) {
        return videoAttachedFile(
            client = client,
            caption = caption,
            fileId = fileId,
            name = safeName,
            fileSizeBytes = fileSize,
            mimeType = mimeType,
            durationSeconds = null,
            thumbnailFileId = thumbnail?.fileId,
        )
    }

    return AttachedFile(
        name = safeName,
        fileSizeBytes = fileSize,
        mimeType = mimeType,
        kind = kind,
        caption = caption,
        loadBytes = { client.downloadFileBytes(fileId) },
    )
}

private fun videoAttachedFile(
    client: TelegramClient,
    caption: String?,
    fileId: String,
    name: String,
    fileSizeBytes: Long?,
    mimeType: String?,
    durationSeconds: Int?,
    thumbnailFileId: String?,
    isAnimation: Boolean = false,
): AttachedFile =
    AttachedFile(
        name = name,
        fileSizeBytes = fileSizeBytes,
        mimeType = mimeType ?: "video/mp4",
        kind = AttachedFileKind.VIDEO,
        caption = caption,
        durationSeconds = durationSeconds,
        // telegram serves bots files of at most 20 MB; the thumbnail is the one frame of an oversize
        // video that still fits, so vision keeps a way in.
        loadThumbnailBytes = thumbnailFileId?.let { id -> suspend { client.downloadFileBytes(id) } },
        isAnimation = isAnimation,
        loadBytes = { client.downloadFileBytes(fileId) },
    )

private fun String?.orVideoName(fileUniqueId: String): String =
    this?.sanitizeFilename()?.takeIf { it.isNotBlank() } ?: "video-$fileUniqueId.mp4"

private fun documentKind(mimeType: String?, name: String): AttachedFileKind {
    val extension = name.substringAfterLast('.', "").lowercase()

    return when {
        mimeType?.startsWith("image/") == true || extension in IMAGE_EXTENSIONS -> AttachedFileKind.IMAGE
        mimeType?.startsWith("video/") == true || extension in VIDEO_EXTENSIONS -> AttachedFileKind.VIDEO
        else -> AttachedFileKind.OTHER
    }
}

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

private val VIDEO_EXTENSIONS =
    setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "wmv", "flv", "mpeg", "mpg", "3gp", "ogv")

private fun Message.toReplySummary(botUserId: Long): RepliedMessageSummary? =
    replyToMessage?.summarizeInternalReply(botUserId)
        ?: externalReplyInfo?.summarize()
        ?: replyToStory?.let { RepliedMessageSummary(type = "story", textOrCaption = null) }

private fun Message.summarizeInternalReply(botUserId: Long): RepliedMessageSummary =
    RepliedMessageSummary(
        type = contentTypeName(),
        textOrCaption = repliedTextOrNull(),
        author = authorLabel(botUserId),
        metadata = mediaMetadataLines(),
    )

// a channel post forwarded into a discussion group and an anonymous admin both arrive without a sender
// user, and are named by the chat that posted them instead.
private fun Message.authorLabel(botUserId: Long): String? {
    val sender = from ?: return senderChat?.titleOrDisplayName().authorValueOrNull()

    if (sender.id == botUserId) return "you"

    return listOfNotNull(displayName(sender.firstName, sender.lastName), sender.userName?.let { "@$it" })
        .joinToString(" ")
        .authorValueOrNull()
}

// a display name is whatever its owner typed, and it lands on a line of its own inside the block.
private fun String?.authorValueOrNull(): String? =
    this?.collapseWhitespaceAndCap(MAX_AUTHOR_CHARS)?.takeIf { it.isNotBlank() }?.neutralizePromptBlocks()

// a quoted rich message keeps its layout: collapsing a tree of headings, lists and code into one
// line leaves the model guessing at the structure it is being asked about.
private fun Message.repliedTextOrNull(): String? =
    richMessage?.toRichMarkdown()?.takeIf { it.isNotBlank() }?.limitTo(MAX_REPLIED_TEXT_CHARS)
        ?: textSnippetOrNull()?.collapseWhitespaceAndCap(MAX_REPLIED_TEXT_CHARS)

private fun ExternalReplyInfo.summarize(): RepliedMessageSummary =
    RepliedMessageSummary(
        type = summaryTypeNameOrNull()?.let { "external $it" } ?: "external text message",
        textOrCaption = null,
        metadata = mediaMetadataLines(),
    )
