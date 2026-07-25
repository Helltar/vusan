package com.helltar.vusan.telegram

import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.limitTo
import com.helltar.vusan.common.sanitizeFilename
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.request.AttachedFile
import java.util.Locale
import org.telegram.telegrambots.meta.api.objects.Document
import org.telegram.telegrambots.meta.api.objects.ExternalReplyInfo
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize
import org.telegram.telegrambots.meta.generics.TelegramClient

private const val MAX_REPLIED_TEXT_CHARS = 4096
private const val MAX_REPLIED_HISTORY_TEXT_CHARS = 600

internal data class RepliedMessageSummary(
    val type: String,
    val textOrCaption: String?,
    val metadata: List<String> = emptyList(),
    val transcript: String? = null
)

internal fun isReplyToOtherUser(replyAuthorId: Long?, botUserId: Long): Boolean =
    replyAuthorId != botUserId

internal suspend fun Message.replySummaryOrNull(
    client: TelegramClient,
    voiceTranscriber: VoiceTranscriber?
): RepliedMessageSummary? {
    val base = toReplySummary() ?: return null
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

// gif messages carry both `animation` and `document`, and an animation is not a loadable attachment.
internal fun Message.toAttachedFileOrNull(client: TelegramClient): AttachedFile? =
    photo?.biggestOrNull()?.toAttachedFile(client, caption)
        ?: document?.takeIf { animation == null }?.toAttachedFile(client, caption)

private fun PhotoSize.toAttachedFile(client: TelegramClient, caption: String?): AttachedFile =
    AttachedFile(
        name = "photo.jpg",
        fileSizeBytes = fileSize?.toLong(),
        mimeType = "image/jpeg",
        isImage = true,
        caption = caption,
        loadBytes = { client.downloadFileBytes(fileId) }
    )

private fun Document.toAttachedFile(client: TelegramClient, caption: String?): AttachedFile {
    val safeName = (fileName ?: "file").sanitizeFilename().ifBlank { "file" }
    val mime = mimeType

    return AttachedFile(
        name = safeName,
        fileSizeBytes = fileSize,
        mimeType = mime,
        isImage = mime?.startsWith("image/") == true || safeName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS,
        caption = caption,
        loadBytes = { client.downloadFileBytes(fileId) }
    )
}

internal fun attachedFileContextBlock(file: AttachedFile): String =
    xmlBlock(
        "attached_file",
        buildString {
            appendLine("name: ${file.name}")
            file.fileSizeBytes?.let { appendLine("size: ${formatFileSize(it)}") }
            append("This file is in the codeExecution working directory under this exact name. ")
            if (file.isImage) {
                append("It is an image: call `describeImage` to answer about what is visible, or use `codeExecution` to process it (resize, filter, colors, dimensions).")
            } else {
                append("Read it directly from a codeExecution script (e.g. pandas.read_csv) instead of asking the user to resend it.")
            }
        }
    )

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

private fun formatFileSize(bytes: Long): String =
    when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(Locale.ROOT, bytes / (1024.0 * 1024))
        bytes >= 1024 -> "%.0f KB".format(Locale.ROOT, bytes / 1024.0)
        else -> "$bytes B"
    }

internal fun formatAgentInput(currentMessageText: String, repliedMessage: RepliedMessageSummary): String =
    buildReplyContextPrompt(currentMessageText, repliedMessage) { it }

internal fun formatHistoryInput(currentMessageText: String, repliedMessage: RepliedMessageSummary): String =
    buildReplyContextPrompt(currentMessageText, repliedMessage) {
        it.collapseWhitespaceAndCap(MAX_REPLIED_HISTORY_TEXT_CHARS).orEmpty()
    }

private fun buildReplyContextPrompt(
    currentMessageText: String,
    repliedMessage: RepliedMessageSummary,
    transformText: (String) -> String
): String =
    buildString {
        appendLine("<reply_context>")
        appendLine("- type: ${repliedMessage.type}")

        if (repliedMessage.metadata.isNotEmpty()) {
            appendLine("- metadata:")
            repliedMessage.metadata.forEach { appendLine("  - $it") }
        }

        repliedMessage.textOrCaption?.let { appendLine(xmlBlock("text_caption", transformText(it))) }
        repliedMessage.transcript?.let { appendLine(xmlBlock("audio_transcript", transformText(it))) }

        appendLine("</reply_context>")
        appendLine()
        appendLine("<user_message>")
        appendLine(currentMessageText)
        append("</user_message>")
    }

private fun Message.toReplySummary(): RepliedMessageSummary? =
    replyToMessage?.summarizeInternalReply()
        ?: externalReplyInfo?.summarize()
        ?: replyToStory?.let { RepliedMessageSummary(type = "story", textOrCaption = null) }

private fun Message.summarizeInternalReply(): RepliedMessageSummary =
    RepliedMessageSummary(
        type = contentTypeName(),
        textOrCaption = repliedTextOrNull(),
        metadata = mediaMetadataLines()
    )

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
