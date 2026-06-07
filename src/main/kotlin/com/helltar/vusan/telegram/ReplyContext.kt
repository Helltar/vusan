package com.helltar.vusan.telegram

import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.sanitizeFilename
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.request.AttachedFile
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.files.downloadFile
import dev.inmo.tgbotapi.types.ReplyInfo
import dev.inmo.tgbotapi.types.files.DocumentFile
import dev.inmo.tgbotapi.types.files.PhotoSize
import dev.inmo.tgbotapi.types.files.TelegramMediaFile
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.abstracts.Message
import dev.inmo.tgbotapi.types.message.content.*
import java.util.*

private const val MAX_REPLIED_TEXT_CHARS = 4096
private const val MAX_REPLIED_HISTORY_TEXT_CHARS = 600

internal data class RepliedMessageSummary(
    val type: String,
    val textOrCaption: String?,
    val metadata: List<String> = emptyList(),
    val transcript: String? = null
)

internal fun isReplyToOtherUser(replyAuthorId: Long?, botUserId: Long): Boolean =
    replyAuthorId == null || replyAuthorId != botUserId

internal suspend fun CommonMessage<*>.replySummaryOrNull(
    bot: TelegramBot,
    voiceTranscriber: VoiceTranscriber?
): RepliedMessageSummary? {
    val base = replyInfo.toReplySummary() ?: return null
    val transcript = transcribeRepliedAudioOrNull(bot, voiceTranscriber)
    return transcript?.let { base.copy(transcript = it) } ?: base
}

private suspend fun CommonMessage<*>.transcribeRepliedAudioOrNull(
    bot: TelegramBot,
    voiceTranscriber: VoiceTranscriber?
): String? {
    if (voiceTranscriber == null) return null

    val replyInfo = replyInfo as? ReplyInfo.Internal ?: return null
    val content = (replyInfo.message as? ContentMessage<*>)?.content ?: return null

    val audioInput =
        when (content) {
            is VoiceContent -> content.media.toAudioInput()
            is AudioContent -> content.media.toAudioInput()
            else -> return null
        }

    return when (val result = voiceTranscriber.transcribe(bot, audioInput)) {
        is VoiceTranscriptionResult.Success -> result.text
        else -> null
    }
}

internal fun CommonMessage<*>.repliedAttachedFileOrNull(bot: TelegramBot): AttachedFile? {
    val replyInfo = replyInfo as? ReplyInfo.Internal ?: return null
    return (replyInfo.message as? ContentMessage<*>)?.content?.toAttachedFileOrNull(bot)
}

internal fun MessageContent.toAttachedFileOrNull(bot: TelegramBot): AttachedFile? =
    when (this) {
        is PhotoContent -> media.toAttachedFile(bot, caption = text)
        is DocumentContent -> media.toAttachedFile(bot, caption = text)
        else -> null
    }

private fun PhotoSize.toAttachedFile(bot: TelegramBot, caption: String?): AttachedFile {
    val media = this
    return AttachedFile(
        name = "photo.jpg",
        fileSizeBytes = fileSize?.bytes?.toLong(),
        mimeType = "image/jpeg",
        isImage = true,
        caption = caption,
        loadBytes = { bot.downloadFile(media) }
    )
}

private fun DocumentFile.toAttachedFile(bot: TelegramBot, caption: String?): AttachedFile {
    val media = this
    val safeName = (fileName ?: "file").sanitizeFilename().ifBlank { "file" }
    val mime = mimeType?.raw
    return AttachedFile(
        name = safeName,
        fileSizeBytes = fileSize?.bytes?.toLong(),
        mimeType = mime,
        isImage = mime?.startsWith("image/") == true || safeName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS,
        caption = caption,
        loadBytes = { bot.downloadFile(media) }
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

private fun ReplyInfo?.toReplySummary(): RepliedMessageSummary? =
    when (this) {
        is ReplyInfo.Internal -> message.summarizeInternalReply()

        is ReplyInfo.External.Text -> RepliedMessageSummary(type = "external text message", textOrCaption = null)

        is ReplyInfo.External.Content -> RepliedMessageSummary(
            type = "external ${content.contentTypeName()}",
            textOrCaption = null,
            metadata = (content as? TelegramMediaFile)?.toMetadataLines().orEmpty()
        )

        is ReplyInfo.ToStory -> RepliedMessageSummary(type = "story", textOrCaption = null)
        null -> null
    }

private fun Message.summarizeInternalReply(): RepliedMessageSummary {
    val content = (this as? ContentMessage<*>)?.content
        ?: return RepliedMessageSummary(type = contentTypeName(), textOrCaption = null)

    return RepliedMessageSummary(
        type = content.contentTypeName(),
        textOrCaption = (content as? TextedContent)?.text?.collapseWhitespaceAndCap(MAX_REPLIED_TEXT_CHARS),
        metadata = (content as? MediaContent)?.media?.toMetadataLines().orEmpty()
    )
}
