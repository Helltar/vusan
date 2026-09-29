package com.helltar.vusan.telegram.delivery

import com.helltar.vusan.common.rethrowIfCancellation
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
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

// cheaper models also keep answering in markdown, and a fenced block or a backticked span lands in
// the chat as literal backticks: telegram's html mode accepts the send and shows them as typed. both
// map onto html one to one, so they are repaired the same way. one pass handles the fence before the
// span, so a backtick inside converted code is never read as markup again; a run of backticks that is
// not a fence stays as typed rather than being half-converted.
private val markdownCodeRegex =
    Regex(
        """```([\w+#.-]*)[^\S\n]*\n(.*?)\n?```|(?<!`)`([^`\n]+)`(?!`)""",
        RegexOption.DOT_MATCHES_ALL,
    )

// a backtick inside html the model already wrote as code is code, not markup. these regions are
// skipped whole, which also keeps canned texts and repaired fences from being touched twice.
private val htmlCodeRegex = Regex("""<(pre|code)\b.*?</\1>""", RegexOption.DOT_MATCHES_ALL)

// markdown code is raw text, but the prompt asks for entities inside `<pre>` too, so a model mixing
// the two conventions has already written `&lt;` here and there. an entity survives as it is and only
// a bare character is escaped, which reads right in html mode either way.
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

// every fallback here degrades the payload, which cannot help when the chat refuses the bot rather
// than the content: the document retry and the text retry would each buy one more rejection. stop the
// cascade and let the caller act on it — delivery reports the chat, the scheduler parks its tasks.
internal fun rethrowIfChatUnreachable(error: Throwable) {
    if (error.isChatUnreachable()) throw error
}

// same reasoning as an unreachable chat: flood control says nothing about the payload, so degrading a
// photo to a document only spends a second rejected request on it. the send is worth repeating whole,
// which [withFloodWaitRetry] does once the cascade has let the error back out.
internal fun rethrowIfRateLimited(error: Throwable) {
    if (error.retryAfterOrNull() != null) throw error
}

// what a turn will still stand still for. telegram can ask for minutes at a time, and a wait that long
// is worse than an unsent message: the user is left with a silent chat and the turn holds its lock.
private val MAX_FLOOD_WAIT = 30.seconds

/**
 * Wait out Telegram's flood control once, then send again.
 *
 * The whole [send] is repeated rather than the single request under it, because every payload is
 * rebuilt from the [com.helltar.vusan.outbox.BotOutput] on each attempt — the byte streams a first
 * attempt consumed are not reusable, and rebuilding is what makes a retry safe at all.
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
            log.warn(e) { "$mediaLabel failed for chat=${target.chatId}, retrying as document" }
            sendDocumentWithCaptionFallback(client, target, bytes, filename, caption, replyParameters, formattingFileNotice)
        }
        .onFailure { e ->
            e.rethrowIfCancellation()
            rethrowIfReplyNotFound(e, replyParameters)
            rethrowIfRateLimited(e)
            rethrowIfChatUnreachable(e)
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
        log.warn(e) { "Markdown document fallback failed for chat=${target.chatId}, sending plain text" }
        sendTextMessage(client, target, markdown, parseMode = null, replyParameters = replyParameters)
    }.getOrThrow()
}
