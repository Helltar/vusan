package com.helltar.vusan.telegram

import com.helltar.vusan.common.rethrowIfCancellation
import io.github.oshai.kotlinlogging.KotlinLogging
import org.telegram.telegrambots.meta.api.methods.ParseMode
import org.telegram.telegrambots.meta.api.objects.ReplyParameters
import org.telegram.telegrambots.meta.generics.TelegramClient

// what to do when Telegram rejects a send: retry without formatting, degrade media to a document,
// or deliver the text as a file. these combinators know nothing about individual output kinds —
// TelegramOutputSender picks which one wraps each send and supplies the last-resort lambda.

internal const val FALLBACK_DOCUMENT_FILENAME = "message.html"
internal const val MARKDOWN_DOCUMENT_FILENAME = "message.md"

private val log = KotlinLogging.logger("TelegramSendFallbacks")

// models keep emitting `<br>` for line breaks despite the prompt, and telegram rejects the whole
// message over any unsupported tag. the replacement is lossless, so fix it here instead of failing
// the send and degrading to the document fallback.
private val brTagRegex = Regex("""</?br\s*/?>""", RegexOption.IGNORE_CASE)

internal fun String.withBrTagsAsNewlines(): String = replace(brTagRegex, "\n")

internal fun rethrowIfReplyNotFound(error: Throwable, replyParameters: ReplyParameters?) {
    if (replyParameters != null && error.isReplyMessageNotFound()) throw error
}

internal suspend fun sendWithHtmlFallback(send: suspend (parseMode: String?) -> Unit) {
    runCatching { send(ParseMode.HTML) }
        .recoverCatching { e ->
            if (e.isEntityParseError()) {
                log.warn { "Telegram rejected HTML, retrying as plain text" }
                send(null)
            } else throw e
        }
        .getOrThrow()
}

// captions share the reply-text formatting policy: a rejected caption would otherwise degrade to
// literal HTML tags, so the media is resent captionless and the caption arrives as a document,
// same as [TelegramOutputSender.sendReplyText].
internal suspend fun sendWithCaptionHtmlFallback(
    client: TelegramClient,
    chatId: Long,
    caption: String?,
    replyParameters: ReplyParameters?,
    formattingFileNotice: String,
    send: suspend (caption: String?, parseMode: String?) -> Unit
) {
    if (caption == null) {
        send(null, null)
        return
    }

    val html = caption.withBrTagsAsNewlines()

    runCatching { send(html, ParseMode.HTML) }
        .recoverCatching { e ->
            if (e.isEntityParseError()) {
                log.warn { "Telegram rejected caption HTML, sending the caption as a $FALLBACK_DOCUMENT_FILENAME file" }
                send(null, null)
                sendTextAsDocument(client, chatId, html, formattingFileNotice, replyParameters)
            } else throw e
        }
        .getOrThrow()
}

internal suspend fun sendMediaWithDocumentFallback(
    client: TelegramClient,
    chatId: Long,
    replyParameters: ReplyParameters?,
    mediaLabel: String,
    bytes: ByteArray,
    filename: String,
    caption: String?,
    formattingFileNotice: String,
    send: suspend () -> Unit,
    onTextFallback: suspend () -> Unit = {}
) {
    runCatching { send() }
        .recoverCatching { e ->
            e.rethrowIfCancellation()
            rethrowIfReplyNotFound(e, replyParameters)
            log.warn(e) { "$mediaLabel failed for chat=$chatId, retrying as document" }
            sendDocumentWithCaptionFallback(client, chatId, bytes, filename, caption, replyParameters, formattingFileNotice)
        }
        .onFailure { e ->
            e.rethrowIfCancellation()
            rethrowIfReplyNotFound(e, replyParameters)
            log.warn(e) { "$mediaLabel document fallback failed for chat=$chatId, falling back to text" }
            onTextFallback()
        }
}

internal suspend fun sendOrFallback(
    chatId: Long,
    replyParameters: ReplyParameters?,
    failureMessage: String,
    send: suspend () -> Unit,
    onFallback: suspend () -> Unit = {}
) {
    runCatching { send() }.onFailure { e ->
        e.rethrowIfCancellation()
        rethrowIfReplyNotFound(e, replyParameters)
        log.warn(e) { "$failureMessage chat=$chatId" }
        onFallback()
    }
}

internal suspend fun sendDocumentWithCaptionFallback(
    client: TelegramClient,
    chatId: Long,
    bytes: ByteArray,
    filename: String,
    caption: String?,
    replyParameters: ReplyParameters?,
    formattingFileNotice: String
) {
    sendWithCaptionHtmlFallback(client, chatId, caption, replyParameters, formattingFileNotice) { text, parseMode ->
        sendDocumentFile(client, chatId, bytes, filename, text, parseMode, replyParameters)
    }
}

internal suspend fun sendTextAsDocument(
    client: TelegramClient,
    chatId: Long,
    text: String,
    notice: String,
    replyParameters: ReplyParameters?
) {
    runCatching {
        sendDocumentFile(
            client,
            chatId,
            htmlReplyDocument(text).encodeToByteArray(),
            FALLBACK_DOCUMENT_FILENAME,
            caption = notice,
            parseMode = null,
            replyParameters = replyParameters
        )
    }.recoverCatching { e ->
        e.rethrowIfCancellation()
        log.warn(e) { "Document fallback failed for chat=$chatId, sending plain text" }
        sendTextMessage(client, chatId, text, parseMode = null, replyParameters = replyParameters)
    }.getOrThrow()
}

internal suspend fun sendMarkdownDocument(
    client: TelegramClient,
    chatId: Long,
    markdown: String,
    replyParameters: ReplyParameters?
) {
    runCatching {
        sendDocumentFile(
            client,
            chatId,
            markdown.encodeToByteArray(),
            MARKDOWN_DOCUMENT_FILENAME,
            caption = null,
            parseMode = null,
            replyParameters = replyParameters
        )
    }.recoverCatching { e ->
        e.rethrowIfCancellation()
        log.warn(e) { "Markdown document fallback failed for chat=$chatId, sending plain text" }
        sendTextMessage(client, chatId, markdown, parseMode = null, replyParameters = replyParameters)
    }.getOrThrow()
}
