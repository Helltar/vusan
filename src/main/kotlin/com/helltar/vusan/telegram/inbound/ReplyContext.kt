package com.helltar.vusan.telegram.inbound

import com.helltar.vusan.agent.RepliedMessageSummary
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.limitTo
import com.helltar.vusan.common.sanitizeFilename
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import com.helltar.vusan.request.attachedFileKindOf
import com.helltar.vusan.request.isAnimationFile
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

// a first name, last name and username, with room for telegram's own limits.
private const val MAX_AUTHOR_CHARS = 160

// the part of the replied message the sender selected, often the whole point ("what is this?" about one term
// in a long answer). it matters for replies to the bot too: the message is in history, but which piece the
// user pointed at is not.
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

    val replied = realReplyOrNull ?: return null
    val audioInput = replied.voice?.toAudioInput() ?: replied.audio?.toAudioInput() ?: return null

    return when (val result = voiceTranscriber.transcribe(client, audioInput)) {
        is VoiceTranscriptionResult.Success -> result.text
        else -> null
    }
}

internal fun Message.repliedAttachedFileOrNull(client: TelegramClient): AttachedFile? =
    realReplyOrNull?.toAttachedFileOrNull(client)

// gif messages carry both `animation` and `document`, so the animation is matched first and the document copy
// never becomes a second attachment.
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
    val kind = attachedFileKindOf(safeName, mimeType)

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
            isAnimation = isAnimationFile(safeName, mimeType),
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
        // telegram serves bots at most 20 MB; the thumbnail is the one frame of an oversize video that still
        // fits, so vision keeps a way in.
        loadThumbnailBytes = thumbnailFileId?.let { id -> suspend { client.downloadFileBytes(id) } },
        isAnimation = isAnimation,
        loadBytes = { client.downloadFileBytes(fileId) },
    )

private fun String?.orVideoName(fileUniqueId: String): String =
    this?.sanitizeFilename()?.takeIf { it.isNotBlank() } ?: "video-$fileUniqueId.mp4"

private fun Message.toReplySummary(botUserId: Long): RepliedMessageSummary? =
    realReplyOrNull?.summarizeInternalReply(botUserId)
        ?: externalReplyInfo?.summarize()
        ?: replyToStory?.let { RepliedMessageSummary(type = "story", textOrCaption = null) }

private fun Message.summarizeInternalReply(botUserId: Long): RepliedMessageSummary =
    RepliedMessageSummary(
        type = contentTypeName(),
        textOrCaption = repliedTextOrNull(),
        author = authorLabel(botUserId),
        metadata = mediaMetadataLines(),
    )

// a channel post forwarded into a discussion group and an anonymous admin arrive without a sender user, and
// are named by the posting chat instead.
private fun Message.authorLabel(botUserId: Long): String? {
    val sender = from ?: return senderChat?.titleOrDisplayName().authorValueOrNull()

    if (sender.id == botUserId) return "you"

    return listOfNotNull(displayName(sender.firstName, sender.lastName), sender.userName?.let { "@$it" })
        .joinToString(" ")
        .authorValueOrNull()
}

// a display name is user text; the block defuses it, so this only keeps it one short line.
private fun String?.authorValueOrNull(): String? =
    this?.collapseWhitespaceAndCap(MAX_AUTHOR_CHARS)?.takeIf { it.isNotBlank() }

// a quoted rich message keeps its layout: collapsing headings, lists and code into one line leaves the model
// guessing at the structure asked about.
private fun Message.repliedTextOrNull(): String? =
    richMessage?.toRichMarkdown()?.takeIf { it.isNotBlank() }?.limitTo(MAX_REPLIED_TEXT_CHARS)
        ?: textSnippetOrNull()?.collapseWhitespaceAndCap(MAX_REPLIED_TEXT_CHARS)

private fun ExternalReplyInfo.summarize(): RepliedMessageSummary =
    RepliedMessageSummary(
        type = summaryTypeNameOrNull()?.let { "external $it" } ?: "external text message",
        textOrCaption = null,
        metadata = mediaMetadataLines(),
    )
