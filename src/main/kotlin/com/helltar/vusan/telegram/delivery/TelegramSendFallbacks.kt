package com.helltar.vusan.telegram.delivery

import com.helltar.vusan.common.rethrowIfCancellation
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import org.telegram.telegrambots.meta.api.methods.ParseMode
import org.telegram.telegrambots.meta.api.objects.ReplyParameters
import org.telegram.telegrambots.meta.generics.TelegramClient

// what to do when telegram rejects a send: retry without formatting, degrade media to a document, or send the
// text as a file. these know nothing of output kinds; TelegramOutputSender picks the wrapper per send and
// supplies the last resort.

internal const val FALLBACK_DOCUMENT_FILENAME = "message.html"
internal const val MARKDOWN_DOCUMENT_FILENAME = "message.md"

private val log = KotlinLogging.logger("TelegramSendFallbacks")

// models keep emitting `<br>` despite the prompt, and telegram rejects the whole message over an unsupported
// tag. the fix is lossless, so it is applied here.
private val brTagRegex = Regex("""</?br\s*/?>""", RegexOption.IGNORE_CASE)

// cheaper models also answer in markdown, and telegram's html mode shows fences and backticked spans as
// typed. both map onto html one to one, so they are repaired too. the fence goes first in one pass, so a
// backtick inside converted code is never reparsed; a backtick run that is not a fence stays as typed.
private val markdownCodeRegex =
    Regex(
        """```([\w+#.-]*)[^\S\n]*\n(.*?)\n?```|(?<!`)`([^`\n]+)`(?!`)""",
        RegexOption.DOT_MATCHES_ALL,
    )

// backticks inside html code the model wrote are code, not markup; these regions are skipped whole, which
// also keeps canned texts and repaired fences from being touched twice.
private val htmlCodeRegex = Regex("""<(pre|code)\b.*?</\1>""", RegexOption.DOT_MATCHES_ALL)

// the prompt asks for entities inside `<pre>`, so a model mixing conventions has already written `&lt;` in
// places. existing entities survive and only bare characters are escaped.
private val bareAmpersandRegex = Regex("""&(?!(?:[a-zA-Z]+|#\d+|#x[0-9a-fA-F]+);)""")

internal fun String.withModelMarkupRepaired(): String =
    replace(brTagRegex, "\n").withMarkdownCodeAsHtml()

private fun String.withMarkdownCodeAsHtml(): String {
    if (!contains('`')) return this

    val repaired = StringBuilder()
    var rest = 0

    for (region in htmlCodeRegex.findAll(this)) {
        repaired.append(substring(rest, region.range.first).replace(markdownCodeRegex, ::markdownCodeAsHtml))
        repaired.append(region.value)
        rest = region.range.last + 1
    }
    repaired.append(substring(rest).replace(markdownCodeRegex, ::markdownCodeAsHtml))

    return repaired.toString()
}

private fun markdownCodeAsHtml(match: MatchResult): String {
    val (language, block, span) = match.destructured

    return when {
        span.isNotEmpty() -> "<code>${span.escapeCodeHtml()}</code>"
        language.isNotEmpty() -> "<pre><code class=\"language-$language\">${block.trimEnd().escapeCodeHtml()}</code></pre>"
        else -> "<pre>${block.trimEnd().escapeCodeHtml()}</pre>"
    }
}

private fun String.escapeCodeHtml(): String =
    replace(bareAmpersandRegex, "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

internal fun rethrowIfReplyNotFound(error: Throwable, replyParameters: ReplyParameters?) {
    if (replyParameters != null && error.isReplyMessageNotFound()) throw error
}

// every fallback degrades the payload, which cannot help when the chat refuses the bot itself: each retry
// would buy one more rejection. stop the cascade and let the caller act: delivery reports the chat, the
// scheduler parks its tasks.
internal fun rethrowIfChatUnreachable(error: Throwable) {
    if (error.isChatUnreachable()) throw error
}

// flood control says nothing about the payload either, so degrading only spends another rejected request. the
// whole send is worth repeating, which [withFloodWaitRetry] does once the error is out.
internal fun rethrowIfRateLimited(error: Throwable) {
    if (error.retryAfterOrNull() != null) throw error
}

// an unanswered send may have arrived, and every fallback resends in another shape: a video that timed out
// while telegram processed it would show up twice.
internal fun rethrowIfUnanswered(error: Throwable) {
    if (error.isUnanswered()) throw error
}

// the longest wait a turn stands for. telegram can ask for minutes, which is worse than an unsent message: a
// silent chat and a turn holding its lock.
private val MAX_FLOOD_WAIT = 30.seconds

/**
 * Waits out telegram's flood control once, then sends again. The whole [send] is repeated, since each attempt
 * rebuilds the payload from the [com.helltar.vusan.outbox.BotOutput]: byte streams a first attempt consumed
 * are not reusable.
 */
internal suspend fun withFloodWaitRetry(chatId: Long, send: suspend () -> Unit) {
    runCatching { send() }
        .recoverCatching { error ->
            val wait = error.retryAfterOrNull()?.takeIf { it <= MAX_FLOOD_WAIT } ?: throw error

            log.warn { "flood control on chat=$chatId, waiting ${wait.inWholeSeconds}s before one retry" }
            delay(wait)
            send()
        }
        .getOrThrow()
}

/**
 * Sends a message for one person's eyes anchored by [replyParameters], and again unanchored when telegram
 * refuses the anchor (the ephemeral command came in over 15 seconds ago). Unanchored, only an administrator
 * may reach that person, so this can fail too, and the caller says what then.
 */
internal suspend fun sendEphemeralWithAnchorFallback(
    replyParameters: ReplyParameters?,
    send: suspend (replyParameters: ReplyParameters?) -> Unit,
) {
    runCatching { send(replyParameters) }
        .recoverCatching { error ->
            error.rethrowIfCancellation()
            if (replyParameters?.ephemeralMessageId == null || !error.isReplyTargetInvalid()) throw error

            log.warn { "ephemeral reply refused its anchor, sending it unanchored: ${error.message}" }
            send(null)
        }
        .getOrThrow()
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

// a rejected caption would otherwise degrade to literal HTML tags, so the media is resent without it and the
// caption arrives as a document, as in [TelegramOutputSender.sendReplyText].
internal suspend fun sendWithCaptionHtmlFallback(
    client: TelegramClient,
    target: ChatTarget,
    caption: String?,
    replyParameters: ReplyParameters?,
    formattingFileNotice: String,
    send: suspend (caption: String?, parseMode: String?) -> Unit,
) {
    if (caption == null) {
        send(null, null)
        return
    }

    val html = caption.withModelMarkupRepaired()

    runCatching { send(html, ParseMode.HTML) }
        .recoverCatching { e ->
            if (e.isEntityParseError()) {
                log.warn { "Telegram rejected caption HTML, sending the caption as a $FALLBACK_DOCUMENT_FILENAME file" }
                send(null, null)
                sendTextAsDocument(client, target, html, formattingFileNotice, replyParameters)
            } else throw e
        }
        .getOrThrow()
}

internal suspend fun sendMediaWithDocumentFallback(
    client: TelegramClient,
    target: ChatTarget,
    replyParameters: ReplyParameters?,
    mediaLabel: String,
    bytes: ByteArray,
    filename: String,
    caption: String?,
    formattingFileNotice: String,
    send: suspend () -> Unit,
    onTextFallback: suspend () -> Unit = {},
) {
    runCatching { send() }
        .recoverCatching { e ->
            e.rethrowIfCancellation()
            rethrowIfReplyNotFound(e, replyParameters)
            rethrowIfRateLimited(e)
            rethrowIfChatUnreachable(e)
            rethrowIfUnanswered(e)
            log.warn(e) { "$mediaLabel failed for chat=${target.chatId}, retrying as document" }
            sendDocumentWithCaptionFallback(client, target, bytes, filename, caption, replyParameters, formattingFileNotice)
        }
        .onFailure { e ->
            e.rethrowIfCancellation()
            rethrowIfReplyNotFound(e, replyParameters)
            rethrowIfRateLimited(e)
            rethrowIfChatUnreachable(e)
            rethrowIfUnanswered(e)
            log.warn(e) { "$mediaLabel document fallback failed for chat=${target.chatId}, falling back to text" }
            onTextFallback()
        }
}

internal suspend fun sendOrFallback(
    target: ChatTarget,
    replyParameters: ReplyParameters?,
    failureMessage: String,
    send: suspend () -> Unit,
    onFallback: suspend () -> Unit = {},
) {
    runCatching { send() }.onFailure { e ->
        e.rethrowIfCancellation()
        rethrowIfReplyNotFound(e, replyParameters)
        rethrowIfRateLimited(e)
        rethrowIfChatUnreachable(e)
        rethrowIfUnanswered(e)
        log.warn(e) { "$failureMessage chat=${target.chatId}" }
        onFallback()
    }
}

internal suspend fun sendDocumentWithCaptionFallback(
    client: TelegramClient,
    target: ChatTarget,
    bytes: ByteArray,
    filename: String,
    caption: String?,
    replyParameters: ReplyParameters?,
    formattingFileNotice: String,
) {
    sendWithCaptionHtmlFallback(client, target, caption, replyParameters, formattingFileNotice) { text, parseMode ->
        sendDocumentFile(client, target, bytes, filename, text, parseMode, replyParameters)
    }
}

internal suspend fun sendTextAsDocument(
    client: TelegramClient,
    target: ChatTarget,
    text: String,
    notice: String,
    replyParameters: ReplyParameters?,
) {
    runCatching {
        sendDocumentFile(
            client,
            target,
            htmlReplyDocument(text).encodeToByteArray(),
            FALLBACK_DOCUMENT_FILENAME,
            caption = notice,
            parseMode = null,
            replyParameters = replyParameters,
        )
    }.recoverCatching { e ->
        e.rethrowIfCancellation()
        rethrowIfRateLimited(e)
        rethrowIfChatUnreachable(e)
        rethrowIfUnanswered(e)
        log.warn(e) { "Document fallback failed for chat=${target.chatId}, sending plain text" }
        sendTextMessage(client, target, text, parseMode = null, replyParameters = replyParameters)
    }.getOrThrow()
}

internal suspend fun sendMarkdownDocument(
    client: TelegramClient,
    target: ChatTarget,
    markdown: String,
    replyParameters: ReplyParameters?,
) {
    runCatching {
        sendDocumentFile(
            client,
            target,
            markdown.encodeToByteArray(),
            MARKDOWN_DOCUMENT_FILENAME,
            caption = null,
            parseMode = null,
            replyParameters = replyParameters,
        )
    }.recoverCatching { e ->
        e.rethrowIfCancellation()
        rethrowIfRateLimited(e)
        rethrowIfChatUnreachable(e)
        rethrowIfUnanswered(e)
        log.warn(e) { "Markdown document fallback failed for chat=${target.chatId}, sending plain text" }
        sendTextMessage(client, target, markdown, parseMode = null, replyParameters = replyParameters)
    }.getOrThrow()
}
