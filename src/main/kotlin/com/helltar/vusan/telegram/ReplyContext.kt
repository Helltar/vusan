package com.helltar.vusan.telegram

import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.request.RepliedPhoto
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.files.downloadFile
import dev.inmo.tgbotapi.types.ReplyInfo
import dev.inmo.tgbotapi.types.files.TelegramMediaFile
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.abstracts.Message
import dev.inmo.tgbotapi.types.message.content.AudioContent
import dev.inmo.tgbotapi.types.message.content.MediaContent
import dev.inmo.tgbotapi.types.message.content.PhotoContent
import dev.inmo.tgbotapi.types.message.content.TextedContent
import dev.inmo.tgbotapi.types.message.content.VoiceContent

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

internal fun CommonMessage<*>.repliedPhotoOrNull(bot: TelegramBot): RepliedPhoto? {
    val replyInfo = replyInfo as? ReplyInfo.Internal ?: return null
    val content = (replyInfo.message as? ContentMessage<*>)?.content as? PhotoContent ?: return null
    val photo = content.media

    return RepliedPhoto(
        fileId = photo.fileId.fileId,
        fileUniqueId = photo.fileUniqueId.string,
        width = photo.width,
        height = photo.height,
        fileSizeBytes = photo.fileSize?.bytes,
        caption = content.text,
        loadBytes = { bot.downloadFile(photo) }
    )
}

internal fun formatAgentInput(currentMessageText: String, repliedMessage: RepliedMessageSummary): String =
    buildReplyContextPrompt(currentMessageText, repliedMessage, includePhotoHint = true) { it }

internal fun formatHistoryInput(currentMessageText: String, repliedMessage: RepliedMessageSummary): String =
    buildReplyContextPrompt(currentMessageText, repliedMessage, includePhotoHint = false) {
        it.collapseWhitespaceAndCap(MAX_REPLIED_HISTORY_TEXT_CHARS).orEmpty()
    }

private fun buildReplyContextPrompt(
    currentMessageText: String,
    repliedMessage: RepliedMessageSummary,
    includePhotoHint: Boolean,
    transformText: (String) -> String
): String =
    buildString {
        appendLine("<reply_context>")
        appendLine("- type: ${repliedMessage.type}")

        if (repliedMessage.metadata.isNotEmpty()) {
            appendLine("- metadata:")
            repliedMessage.metadata.forEach { appendLine("  - $it") }
        }

        if (includePhotoHint && repliedMessage.type == "photo") {
            appendLine("- visual content: call `describeRepliedPhoto` if the user's request depends on what is visible or on OCR.")
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

