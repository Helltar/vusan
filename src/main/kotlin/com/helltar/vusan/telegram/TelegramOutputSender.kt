package com.helltar.vusan.telegram

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.outbox.BotOutput
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.send.media.*
import dev.inmo.tgbotapi.extensions.api.send.polls.sendQuizPoll
import dev.inmo.tgbotapi.extensions.api.send.polls.sendRegularPoll
import dev.inmo.tgbotapi.extensions.api.send.sendRichMessage
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.api.send.setMessageReaction
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import dev.inmo.tgbotapi.requests.abstracts.toInputFile
import dev.inmo.tgbotapi.types.ChatIdentifier
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.ReplyParameters
import dev.inmo.tgbotapi.types.media.TelegramMediaDocument
import dev.inmo.tgbotapi.types.media.TelegramMediaPhoto
import dev.inmo.tgbotapi.types.message.HTMLParseMode
import dev.inmo.tgbotapi.types.message.ParseMode
import dev.inmo.tgbotapi.types.polls.InputPollOption
import dev.inmo.tgbotapi.types.rich.InputRichMessageMarkdown
import dev.inmo.tgbotapi.utils.extensions.toHtml
import io.github.oshai.kotlinlogging.KotlinLogging

internal object TelegramOutputSender {

    private const val FALLBACK_DOCUMENT_FILENAME = "message.html"
    private const val MARKDOWN_DOCUMENT_FILENAME = "message.md"
    private const val VIDEO_THUMBNAIL_FILENAME = "thumbnail.jpg"
    private const val VIDEO_COVER_FILENAME = "cover.jpg"

    private val log = KotlinLogging.logger {}

    suspend fun send(
        bot: TelegramBot,
        item: BotOutput,
        chatId: ChatIdentifier,
        replyParameters: ReplyParameters?,
        caption: String?,
        formattingFileNotice: String
    ) {
        when (item) {
            is BotOutput.Text -> sendReplyText(bot, chatId, item.text, replyParameters, formattingFileNotice)
            is BotOutput.RichMessage -> sendRichMessage(bot, chatId, item.markdown, replyParameters)
            is BotOutput.Animation -> sendAnimation(bot, chatId, replyParameters, item, caption, formattingFileNotice)
            is BotOutput.Photo -> sendPhoto(bot, chatId, replyParameters, item, caption, formattingFileNotice)
            is BotOutput.PhotoGroup -> sendPhotoGroup(bot, chatId, replyParameters, item, formattingFileNotice)
            is BotOutput.Document -> sendDocument(bot, chatId, replyParameters, item, caption, formattingFileNotice)
            is BotOutput.DocumentGroup -> sendDocumentGroup(bot, chatId, replyParameters, item, formattingFileNotice)
            is BotOutput.Audio -> sendAudio(bot, chatId, replyParameters, item, caption, formattingFileNotice)
            is BotOutput.Voice -> sendVoice(bot, chatId, replyParameters, item, caption, formattingFileNotice)
            is BotOutput.Video -> sendVideo(bot, chatId, replyParameters, item, caption, formattingFileNotice)
            is BotOutput.VideoNote -> sendVideoNote(bot, chatId, replyParameters, item, formattingFileNotice)
            is BotOutput.Quiz -> sendQuiz(bot, chatId, replyParameters, item)
            is BotOutput.Poll -> sendPoll(bot, chatId, replyParameters, item)
            is BotOutput.Reaction -> sendReaction(bot, chatId, item)
        }
    }

    private suspend fun sendReaction(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        reaction: BotOutput.Reaction
    ) {
        runCatching {
            bot.setMessageReaction(
                chatId = chatId,
                messageId = MessageId(reaction.messageId),
                emoji = reaction.emoji
            )
        }.onFailure { e ->
            e.rethrowIfCancellation()
            log.warn(e) {
                "setMessageReaction failed chat=$chatId message=${reaction.messageId} emoji=[${reaction.emoji}]"
            }
        }
    }

    suspend fun sendText(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        text: String,
        replyParameters: ReplyParameters?
    ) {
        sendWithHtmlFallback { parseMode ->
            bot.sendTextMessage(
                chatId = chatId,
                text = text,
                parseMode = parseMode,
                replyParameters = replyParameters
            )
        }
    }

    // agent reply text can carry malformed HTML the model produced. when Telegram rejects it, deliver the raw
    // text as a document (with a short note explaining why) instead of re-sending it unformatted, so the user
    // still gets the intended structure. a bot-authored notice goes through plain [sendText] instead.
    suspend fun sendReplyText(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        text: String,
        replyParameters: ReplyParameters?,
        formattingFileNotice: String
    ) {
        runCatching {
            bot.sendTextMessage(
                chatId = chatId,
                text = text,
                parseMode = HTMLParseMode,
                replyParameters = replyParameters
            )
        }.recoverCatching { e ->
            if (e.isEntityParseError()) {
                log.warn { "Telegram rejected HTML, sending the reply as a $FALLBACK_DOCUMENT_FILENAME file" }
                sendTextAsDocument(bot, chatId, text, formattingFileNotice, replyParameters)
            } else throw e
        }.getOrThrow()
    }

    // opt-in rich messages carry github-flavored markdown. if Telegram rejects the send, deliver the same
    // source as a .md document — clients render markdown inline and a document is not bound by the 4096-char
    // text limit. reply-not-found propagates so the caller can retry without the anchor.
    private suspend fun sendRichMessage(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        markdown: String,
        replyParameters: ReplyParameters?
    ) {
        runCatching {
            bot.sendRichMessage(
                chatId = chatId,
                richMessage = InputRichMessageMarkdown(markdown),
                replyParameters = replyParameters
            )
        }.recoverCatching { e ->
            e.rethrowIfCancellation()
            rethrowIfReplyNotFound(e, replyParameters)
            log.warn(e) { "sendRichMessage failed for chat=$chatId, resending as a $MARKDOWN_DOCUMENT_FILENAME file" }
            sendMarkdownDocument(bot, chatId, markdown, replyParameters)
        }.getOrThrow()
    }

    private suspend fun sendMarkdownDocument(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        markdown: String,
        replyParameters: ReplyParameters?
    ) {
        runCatching {
            bot.sendDocument(
                chatId = chatId,
                document = markdown.encodeToByteArray().asMultipartFile(MARKDOWN_DOCUMENT_FILENAME),
                replyParameters = replyParameters
            )
        }.recoverCatching { e ->
            e.rethrowIfCancellation()
            log.warn(e) { "Markdown document fallback failed for chat=$chatId, sending plain text" }
            bot.sendTextMessage(chatId = chatId, text = markdown, parseMode = null, replyParameters = replyParameters)
        }.getOrThrow()
    }

    private suspend fun sendTextAsDocument(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        text: String,
        notice: String,
        replyParameters: ReplyParameters?
    ) {
        runCatching {
            bot.sendDocument(
                chatId = chatId,
                document = htmlReplyDocument(text).encodeToByteArray().asMultipartFile(FALLBACK_DOCUMENT_FILENAME),
                text = notice,
                replyParameters = replyParameters
            )
        }.recoverCatching { e ->
            e.rethrowIfCancellation()
            log.warn(e) { "Document fallback failed for chat=$chatId, sending plain text" }
            bot.sendTextMessage(chatId = chatId, text = text, parseMode = null, replyParameters = replyParameters)
        }.getOrThrow()
    }

    private suspend fun sendDocument(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        replyParameters: ReplyParameters?,
        document: BotOutput.Document,
        caption: String?,
        formattingFileNotice: String
    ) = sendOrFallback(
        chatId = chatId,
        replyParameters = replyParameters,
        failureMessage = "sendDocument failed, falling back to text",
        send = {
            sendDocumentWithCaptionFallback(
                bot,
                chatId,
                document.bytes,
                document.filename,
                caption,
                replyParameters,
                formattingFileNotice
            )
        },
        onFallback = captionTextFallback(bot, chatId, caption, replyParameters)
    )

    private suspend fun sendAnimation(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        replyParameters: ReplyParameters?,
        animation: BotOutput.Animation,
        caption: String?,
        formattingFileNotice: String
    ) {
        // generated GIF (bytes): fall back to document so the animation still arrives.
        val bytes = animation.bytes

        if (bytes != null) {
            sendMediaWithDocumentFallback(
                bot = bot,
                chatId = chatId,
                replyParameters = replyParameters,
                mediaLabel = "sendAnimation",
                bytes = bytes,
                filename = animation.filename,
                caption = caption,
                formattingFileNotice = formattingFileNotice,
                onTextFallback = captionTextFallback(bot, chatId, caption, replyParameters),
                send = {
                    sendWithCaptionHtmlFallback(bot, chatId, caption, replyParameters, formattingFileNotice) { text, parseMode ->
                        bot.sendAnimation(
                            chatId = chatId,
                            animation = bytes.asMultipartFile(animation.filename),
                            text = text,
                            parseMode = parseMode,
                            replyParameters = replyParameters
                        )
                    }
                }
            )

            return
        }

        // remote URL-based animation (e.g. Giphy).
        val url = requireNotNull(animation.url)

        sendOrFallback(
            chatId = chatId,
            replyParameters = replyParameters,
            failureMessage = "sendAnimation failed, falling back to text",
            send = {
                sendWithCaptionHtmlFallback(bot, chatId, caption, replyParameters, formattingFileNotice) { text, parseMode ->
                    bot.sendAnimation(
                        chatId = chatId,
                        animation = url.toInputFile(),
                        text = text,
                        parseMode = parseMode,
                        replyParameters = replyParameters
                    )
                }
            },
            onFallback = captionTextFallback(bot, chatId, caption, replyParameters)
        )
    }

    private suspend fun sendPhoto(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        replyParameters: ReplyParameters?,
        photo: BotOutput.Photo,
        caption: String?,
        formattingFileNotice: String
    ) {
        val file = { photo.bytes.asMultipartFile(photo.filename) }

        val send =
            suspend {
                sendWithCaptionHtmlFallback(bot, chatId, caption, replyParameters, formattingFileNotice) { text, parseMode ->
                    bot.sendPhoto(
                        chatId = chatId,
                        fileId = file(),
                        text = text,
                        parseMode = parseMode,
                        replyParameters = replyParameters
                    )
                }
            }

        if (!photo.fallbackToDocument) {
            sendOrFallback(
                chatId = chatId,
                replyParameters = replyParameters,
                failureMessage = "sendPhoto failed, document copy should be delivered separately",
                send = send,
                onFallback = captionTextFallback(bot, chatId, caption, replyParameters)
            )

            return
        }

        sendMediaWithDocumentFallback(
            bot = bot,
            chatId = chatId,
            replyParameters = replyParameters,
            mediaLabel = "sendPhoto",
            bytes = photo.bytes,
            filename = photo.filename,
            caption = caption,
            formattingFileNotice = formattingFileNotice,
            onTextFallback = captionTextFallback(bot, chatId, caption, replyParameters),
            send = send
        )
    }

    private suspend fun sendPhotoGroup(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        replyParameters: ReplyParameters?,
        group: BotOutput.PhotoGroup,
        formattingFileNotice: String
    ) = sendOrFallback(
        chatId = chatId,
        replyParameters = replyParameters,
        failureMessage = "sendPhotoGroup failed, falling back to individual photos",
        send = {
            val media = group.photos.map { TelegramMediaPhoto(file = it.bytes.asMultipartFile(it.filename)) }
            bot.sendVisualMediaGroup(chatId = chatId, media = media, replyParameters = replyParameters)
        },
        onFallback = {
            group.photos.forEach { photo ->
                runCatching { sendPhoto(bot, chatId, replyParameters, photo, caption = null, formattingFileNotice) }
                    .onFailure { ie ->
                        ie.rethrowIfCancellation()
                        log.warn(ie) { "Fallback sendPhoto failed for chat=$chatId" }
                    }
            }
        }
    )

    private suspend fun sendDocumentGroup(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        replyParameters: ReplyParameters?,
        group: BotOutput.DocumentGroup,
        formattingFileNotice: String
    ) = sendOrFallback(
        chatId = chatId,
        replyParameters = replyParameters,
        failureMessage = "sendDocumentGroup failed, falling back to individual documents",
        send = {
            val media = group.documents.map { TelegramMediaDocument(file = it.bytes.asMultipartFile(it.filename)) }
            bot.sendDocumentsGroup(chatId = chatId, media = media, replyParameters = replyParameters)
        },
        onFallback = {
            group.documents.forEach { document ->
                runCatching { sendDocument(bot, chatId, replyParameters, document, caption = null, formattingFileNotice) }
                    .onFailure { ie ->
                        ie.rethrowIfCancellation()
                        log.warn(ie) { "Fallback sendDocument failed for chat=$chatId" }
                    }
            }
        }
    )

    private suspend fun sendAudio(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        replyParameters: ReplyParameters?,
        audio: BotOutput.Audio,
        caption: String?,
        formattingFileNotice: String
    ) {
        val fullCaption = captionWithSourceLink(caption, audio.trackUrl)
        val audioFile = { audio.bytes.asMultipartFile(audio.filename) }

        sendOrFallback(
            chatId = chatId,
            replyParameters = replyParameters,
            failureMessage = "sendAudio failed, falling back to text",
            send = {
                sendWithCaptionHtmlFallback(bot, chatId, fullCaption, replyParameters, formattingFileNotice) { text, parseMode ->
                    bot.sendAudio(
                        chatId = chatId,
                        audio = audioFile(),
                        title = audio.title,
                        performer = audio.performer,
                        duration = audio.durationSeconds?.toLong(),
                        text = text,
                        parseMode = parseMode,
                        replyParameters = replyParameters
                    )
                }
            },
            onFallback = {
                val fallback = listOfNotNull(fullCaption, "${audio.title} — ${audio.performer}").joinToString("\n")
                sendText(bot, chatId, fallback, replyParameters)
            }
        )
    }

    private suspend fun sendVoice(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        replyParameters: ReplyParameters?,
        voice: BotOutput.Voice,
        caption: String?,
        formattingFileNotice: String
    ) {
        val file = { voice.bytes.asMultipartFile("voice.mp3") }

        sendOrFallback(
            chatId = chatId,
            replyParameters = replyParameters,
            failureMessage = "sendVoice failed, falling back to text",
            send = {
                sendWithCaptionHtmlFallback(bot, chatId, caption, replyParameters, formattingFileNotice) { text, parseMode ->
                    bot.sendVoice(
                        chatId = chatId,
                        voice = file(),
                        duration = voice.durationSeconds?.toLong(),
                        text = text,
                        parseMode = parseMode,
                        replyParameters = replyParameters
                    )
                }
            },
            onFallback = captionTextFallback(bot, chatId, caption, replyParameters)
        )
    }

    private suspend fun sendVideo(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        replyParameters: ReplyParameters?,
        video: BotOutput.Video,
        caption: String?,
        formattingFileNotice: String
    ) {
        val fullCaption = captionWithSourceLink(caption, video.sourceUrl)
        val file = { video.bytes.asMultipartFile(video.filename) }
        val thumbnail = video.thumbnail

        sendMediaWithDocumentFallback(
            bot = bot,
            chatId = chatId,
            replyParameters = replyParameters,
            mediaLabel = "sendVideo",
            bytes = video.bytes,
            filename = video.filename,
            caption = fullCaption,
            formattingFileNotice = formattingFileNotice,
            onTextFallback = captionTextFallback(bot, chatId, fullCaption, replyParameters),
            send = {
                sendWithCaptionHtmlFallback(bot, chatId, fullCaption, replyParameters, formattingFileNotice) { text, parseMode ->
                    bot.sendVideo(
                        chatId = chatId,
                        video = file(),
                        thumb = thumbnail?.asMultipartFile(VIDEO_THUMBNAIL_FILENAME),
                        cover = thumbnail?.asMultipartFile(VIDEO_COVER_FILENAME),
                        text = text,
                        parseMode = parseMode,
                        duration = video.durationSeconds?.toLong(),
                        width = video.width,
                        height = video.height,
                        supportsStreaming = true,
                        replyParameters = replyParameters
                    )
                }
            }
        )
    }

    private suspend fun sendVideoNote(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        replyParameters: ReplyParameters?,
        videoNote: BotOutput.VideoNote,
        formattingFileNotice: String
    ) {
        val file = { videoNote.bytes.asMultipartFile("video-note.mp4") }

        sendMediaWithDocumentFallback(
            bot = bot,
            chatId = chatId,
            replyParameters = replyParameters,
            mediaLabel = "sendVideoNote",
            bytes = videoNote.bytes,
            filename = "video-note.mp4",
            caption = null,
            formattingFileNotice = formattingFileNotice,
            send = {
                bot.sendVideoNote(
                    chatId = chatId,
                    videoNote = file(),
                    duration = videoNote.durationSeconds?.toLong(),
                    size = videoNote.size,
                    replyParameters = replyParameters
                )
            }
        )
    }

    private suspend fun sendQuiz(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        replyParameters: ReplyParameters?,
        quiz: BotOutput.Quiz
    ) = sendOrFallback(
        chatId = chatId,
        replyParameters = replyParameters,
        failureMessage = "sendQuiz failed",
        send = {
            bot.sendQuizPoll(
                chatId = chatId,
                question = quiz.question,
                options = quiz.options.map(::InputPollOption),
                correctOptionIds = listOf(quiz.correctOptionIndex),
                explanation = quiz.explanation,
                isAnonymous = quiz.isAnonymous,
                replyParameters = replyParameters
            )
        }
    )

    private suspend fun sendPoll(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        replyParameters: ReplyParameters?,
        poll: BotOutput.Poll
    ) = sendOrFallback(
        chatId = chatId,
        replyParameters = replyParameters,
        failureMessage = "sendPoll failed",
        send = {
            bot.sendRegularPoll(
                chatId = chatId,
                question = poll.question,
                options = poll.options.map(::InputPollOption),
                isAnonymous = poll.isAnonymous,
                allowsMultipleAnswers = poll.allowsMultipleAnswers,
                replyParameters = replyParameters
            )
        }
    )

    private suspend fun sendDocumentWithCaptionFallback(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        bytes: ByteArray,
        filename: String,
        caption: String?,
        replyParameters: ReplyParameters?,
        formattingFileNotice: String
    ) {
        val file = { bytes.asMultipartFile(filename) }

        sendWithCaptionHtmlFallback(bot, chatId, caption, replyParameters, formattingFileNotice) { text, parseMode ->
            bot.sendDocument(
                chatId = chatId,
                document = file(),
                text = text,
                parseMode = parseMode,
                replyParameters = replyParameters
            )
        }
    }

    private suspend fun sendWithHtmlFallback(send: suspend (parseMode: ParseMode?) -> Unit) {
        runCatching { send(HTMLParseMode) }
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
    // same as [sendReplyText].
    private suspend fun sendWithCaptionHtmlFallback(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        caption: String?,
        replyParameters: ReplyParameters?,
        formattingFileNotice: String,
        send: suspend (caption: String?, parseMode: ParseMode?) -> Unit
    ) {
        if (caption == null) {
            send(null, null)
            return
        }

        runCatching { send(caption, HTMLParseMode) }
            .recoverCatching { e ->
                if (e.isEntityParseError()) {
                    log.warn { "Telegram rejected caption HTML, sending the caption as a $FALLBACK_DOCUMENT_FILENAME file" }
                    send(null, null)
                    sendTextAsDocument(bot, chatId, caption, formattingFileNotice, replyParameters)
                } else throw e
            }
            .getOrThrow()
    }

    private suspend fun sendMediaWithDocumentFallback(
        bot: TelegramBot,
        chatId: ChatIdentifier,
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
                sendDocumentWithCaptionFallback(bot, chatId, bytes, filename, caption, replyParameters, formattingFileNotice)
            }
            .onFailure { e ->
                e.rethrowIfCancellation()
                rethrowIfReplyNotFound(e, replyParameters)
                log.warn(e) { "$mediaLabel document fallback failed for chat=$chatId, falling back to text" }
                onTextFallback()
            }
    }

    private suspend fun sendOrFallback(
        chatId: ChatIdentifier,
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

    // deliver the caption as a plain message when the media itself could not be sent at all.
    private fun captionTextFallback(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        caption: String?,
        replyParameters: ReplyParameters?
    ): suspend () -> Unit =
        { caption?.let { sendText(bot, chatId, it, replyParameters) } }

    private fun rethrowIfReplyNotFound(error: Throwable, replyParameters: ReplyParameters?) {
        if (replyParameters != null && error.isReplyMessageNotFound()) throw error
    }
}

/** Appends an HTML source link (e.g. `<a href="url">YouTube</a>`) to the caption; `null` when both are empty. */
private fun captionWithSourceLink(caption: String?, sourceUrl: String?): String? {
    val link = sourceUrl?.let { """<a href="${it.toHtml()}">${trackLinkLabel(it)}</a>""" }
    return listOfNotNull(caption, link).joinToString("\n").ifBlank { null }
}

private fun trackLinkLabel(url: String): String {
    val host = runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")

    return when {
        "youtube.com" in host || "youtu.be" in host || "youtube-nocookie.com" in host -> "YouTube"
        "music.apple.com" in host || "itunes.apple.com" in host -> "Apple Music"
        "soundcloud.com" in host -> "SoundCloud"
        "spotify.com" in host -> "Spotify"
        "bandcamp.com" in host -> "Bandcamp"
        else -> "Source"
    }
}
