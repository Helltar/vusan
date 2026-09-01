package com.helltar.vusan.telegram.inbound

import com.helltar.vusan.agent.neutralizePromptBlocks
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.limitTo
import com.helltar.vusan.common.sanitizeFilename
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import com.helltar.vusan.telegram.downloadFileBytes
import java.util.Locale
import org.telegram.telegrambots.meta.api.objects.Document
import org.telegram.telegrambots.meta.api.objects.ExternalReplyInfo
import org.telegram.telegrambots.meta.api.objects.Video
import org.telegram.telegrambots.meta.api.objects.VideoNote
import org.telegram.telegrambots.meta.api.objects.games.Animation
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize
import org.telegram.telegrambots.meta.generics.TelegramClient

private const val MAX_REPLIED_TEXT_CHARS = 4096
private const val MAX_REPLIED_STORED_TEXT_CHARS = 600
private const val MAX_QUOTED_FRAGMENT_CHARS = 1024

// a first name, a last name and a username, with room to spare for telegram's own limits.
private const val MAX_AUTHOR_CHARS = 160

internal data class RepliedMessageSummary(
    val type: String,
    val textOrCaption: String?,
    // who wrote the message being replied to, `you` when it is the bot's own. in a group the person
    // replying is often not the one that message was written for, so their history carries nothing
    // about it — this block is then all the model gets.
    val author: String? = null,
    val metadata: List<String> = emptyList(),
    val transcript: String? = null
)

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
    botUserId: Long
): RepliedMessageSummary? {
    val base = toReplySummary(botUserId) ?: return null
    val transcript = transcribeRepliedAudioOrNull(client, voiceTranscriber)
    return transcript?.let { base.copy(transcript = it) } ?: base
}

private suspend fun Message.transcribeRepliedAudioOrNull(
    client: TelegramClient,
    voiceTranscriber: VoiceTranscriber?
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
        loadBytes = { client.downloadFileBytes(fileId) }
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
        thumbnailFileId = thumbnail?.fileId
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
        isAnimation = true
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
        thumbnailFileId = thumbnail?.fileId
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
            thumbnailFileId = thumbnail?.fileId
        )
    }

    return AttachedFile(
        name = safeName,
        fileSizeBytes = fileSize,
        mimeType = mimeType,
        kind = kind,
        caption = caption,
        loadBytes = { client.downloadFileBytes(fileId) }
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
    isAnimation: Boolean = false
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
        loadBytes = { client.downloadFileBytes(fileId) }
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

internal fun attachedFileContextBlock(file: AttachedFile): String =
    xmlBlock(
        "attached_file",
        buildString {
            appendLine("name: ${file.name}")
            file.fileSizeBytes?.let { appendLine("size: ${formatFileSize(it)}") }
            file.durationSeconds?.let { appendLine("duration: ${it}s") }

            when (file.kind) {
                AttachedFileKind.IMAGE -> {
                    append("This file is in the codeExecution working directory under this exact name. ")
                    append("It is an image: call `describeImage` to answer about what is visible, or use `codeExecution` to process it (resize, filter, colors, dimensions).")
                }

                // the GIF line has to live here rather than in the no-caption prompt: a caption replaces
                // that prompt, and the reaction still is not something to review.
                AttachedFileKind.VIDEO ->
                    if (file.isAnimation)
                        append("It is a GIF: a short soundless loop, usually thrown into a chat as a reaction rather than as something to review. Call `describeVideo` only when the user asks what is in it, and never narrate it unasked. It is not available to `codeExecution`.")
                    else
                        append("It is a video: call `describeVideo` when your answer depends on what happens in it or what is said in it. It is not available to `codeExecution`.")

                AttachedFileKind.OTHER -> {
                    append("This file is in the codeExecution working directory under this exact name. ")
                    append("Read it directly from a codeExecution script (e.g. pandas.read_csv) instead of asking the user to resend it.")
                }
            }
        }
    )

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

private val VIDEO_EXTENSIONS =
    setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "wmv", "flv", "mpeg", "mpg", "3gp", "ogv")

private fun formatFileSize(bytes: Long): String =
    when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(Locale.ROOT, bytes / (1024.0 * 1024))
        bytes >= 1024 -> "%.0f KB".format(Locale.ROOT, bytes / 1024.0)
        else -> "$bytes B"
    }

internal fun formatAgentInput(
    currentMessageText: String,
    repliedMessage: RepliedMessageSummary?,
    quotedFragment: String?
): String =
    buildReplyContextPrompt(currentMessageText, repliedMessage, quotedFragment) { it }

internal fun formatConversationInput(
    currentMessageText: String,
    repliedMessage: RepliedMessageSummary?,
    quotedFragment: String?
): String =
    buildReplyContextPrompt(currentMessageText, repliedMessage, quotedFragment) {
        it.collapseWhitespaceAndCap(MAX_REPLIED_STORED_TEXT_CHARS).orEmpty()
    }

private fun buildReplyContextPrompt(
    currentMessageText: String,
    repliedMessage: RepliedMessageSummary?,
    quotedFragment: String?,
    transformText: (String) -> String
): String {
    if (repliedMessage == null && quotedFragment == null) return currentMessageText

    // quoting the whole message says nothing beyond the reply itself, and repeating it would read as
    // two different pieces of context.
    val fragment = quotedFragment?.takeUnless { it.trim() == repliedMessage?.textOrCaption?.trim() }

    return buildString {
        if (repliedMessage != null) {
            appendLine("<reply_context>")
            repliedMessage.author?.let { appendLine("- author: $it") }
            appendLine("- type: ${repliedMessage.type}")

            if (repliedMessage.metadata.isNotEmpty()) {
                appendLine("- metadata:")
                repliedMessage.metadata.forEach { appendLine("  - $it") }
            }

            // the quoted message is somebody else's text and never passed through inbound sanitizing.
            repliedMessage.textOrCaption?.let {
                appendLine(xmlBlock("text_caption", transformText(it).neutralizePromptBlocks()))
            }

            repliedMessage.transcript?.let {
                appendLine(xmlBlock("audio_transcript", transformText(it).neutralizePromptBlocks()))
            }

            appendLine("</reply_context>")
            appendLine()
        }

        // last before the request: the fragment is what the request is about.
        fragment?.let {
            appendLine(xmlBlock("quoted_fragment", it.neutralizePromptBlocks()))
            appendLine()
        }

        append(xmlBlock("user_message", currentMessageText))
    }
}

private fun Message.toReplySummary(botUserId: Long): RepliedMessageSummary? =
    replyToMessage?.summarizeInternalReply(botUserId)
        ?: externalReplyInfo?.summarize()
        ?: replyToStory?.let { RepliedMessageSummary(type = "story", textOrCaption = null) }

private fun Message.summarizeInternalReply(botUserId: Long): RepliedMessageSummary =
    RepliedMessageSummary(
        type = contentTypeName(),
        textOrCaption = repliedTextOrNull(),
        author = authorLabel(botUserId),
        metadata = mediaMetadataLines()
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
        metadata = mediaMetadataLines()
    )
