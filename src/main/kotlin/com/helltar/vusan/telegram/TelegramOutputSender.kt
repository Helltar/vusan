package com.helltar.vusan.telegram

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.outbox.BotOutput
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.ByteArrayInputStream
import org.telegram.telegrambots.meta.api.methods.ParseMode
import org.telegram.telegrambots.meta.api.methods.polls.SendPoll
import org.telegram.telegrambots.meta.api.methods.reactions.SetMessageReaction
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation
import org.telegram.telegrambots.meta.api.methods.send.SendAudio
import org.telegram.telegrambots.meta.api.methods.send.SendDocument
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.methods.send.SendVideo
import org.telegram.telegrambots.meta.api.methods.send.SendVideoNote
import org.telegram.telegrambots.meta.api.methods.send.SendVoice
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.ReplyParameters
import org.telegram.telegrambots.meta.api.objects.media.InputMedia
import org.telegram.telegrambots.meta.api.objects.media.InputMediaDocument
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto
import org.telegram.telegrambots.meta.api.objects.polls.input.InputPollOption
import org.telegram.telegrambots.meta.api.objects.reactions.ReactionTypeEmoji
import org.telegram.telegrambots.meta.generics.TelegramClient

internal object TelegramOutputSender {

    private const val FALLBACK_DOCUMENT_FILENAME = "message.html"
    private const val MARKDOWN_DOCUMENT_FILENAME = "message.md"
    private const val VIDEO_THUMBNAIL_FILENAME = "thumbnail.jpg"
    private const val VIDEO_COVER_FILENAME = "cover.jpg"

    private val log = KotlinLogging.logger {}

    // models keep emitting `<br>` for line breaks despite the prompt, and telegram rejects the whole
    // message over any unsupported tag. the replacement is lossless, so fix it here instead of failing
    // the send and degrading to the document fallback.
    private val brTagRegex = Regex("""</?br\s*/?>""", RegexOption.IGNORE_CASE)

    private fun String.withBrTagsAsNewlines(): String = replace(brTagRegex, "\n")

    suspend fun send(
        client: TelegramClient,
        item: BotOutput,
        chatId: Long,
        replyParameters: ReplyParameters?,
        caption: String?,
        formattingFileNotice: String
    ) {
        when (item) {
            is BotOutput.Text -> sendReplyText(client, chatId, item.text, replyParameters, formattingFileNotice)
            is BotOutput.RichMessage -> sendRichMessage(client, chatId, item.markdown, replyParameters)
            is BotOutput.Animation -> sendAnimation(client, chatId, replyParameters, item, caption, formattingFileNotice)
            is BotOutput.Photo -> sendPhoto(client, chatId, replyParameters, item, caption, formattingFileNotice)
            is BotOutput.PhotoGroup -> sendPhotoGroup(client, chatId, replyParameters, item, formattingFileNotice)
            is BotOutput.Document -> sendDocument(client, chatId, replyParameters, item, caption, formattingFileNotice)
            is BotOutput.DocumentGroup -> sendDocumentGroup(client, chatId, replyParameters, item, formattingFileNotice)
            is BotOutput.Audio -> sendAudio(client, chatId, replyParameters, item, caption, formattingFileNotice)
            is BotOutput.Voice -> sendVoice(client, chatId, replyParameters, item, caption, formattingFileNotice)
            is BotOutput.Video -> sendVideo(client, chatId, replyParameters, item, caption, formattingFileNotice)
            is BotOutput.VideoNote -> sendVideoNote(client, chatId, replyParameters, item, formattingFileNotice)
            is BotOutput.Quiz -> sendQuiz(client, chatId, replyParameters, item)
            is BotOutput.Poll -> sendPoll(client, chatId, replyParameters, item)
            is BotOutput.Reaction -> sendReaction(client, chatId, item)
        }
    }

    private suspend fun sendReaction(
        client: TelegramClient,
        chatId: Long,
        reaction: BotOutput.Reaction
    ) {
        runCatching {
            client.api {
                execute(
                    SetMessageReaction.builder()
                        .chatId(chatId.toString())
                        .messageId(reaction.messageId.toInt())
                        .reactionTypes(listOf(ReactionTypeEmoji.builder().emoji(reaction.emoji).build()))
                        .build()
                )
            }
        }.onFailure { e ->
            e.rethrowIfCancellation()
            log.warn(e) {
                "setMessageReaction failed chat=$chatId message=${reaction.messageId} emoji=[${reaction.emoji}]"
            }
        }
    }

    suspend fun sendText(
        client: TelegramClient,
        chatId: Long,
        text: String,
        replyParameters: ReplyParameters?
    ) {
        val html = text.withBrTagsAsNewlines()

        sendWithHtmlFallback { parseMode ->
            sendTextMessage(client, chatId, html, parseMode, replyParameters)
        }
    }

    // agent reply text can carry malformed HTML the model produced. when Telegram rejects it, deliver the raw
    // text as a document (with a short note explaining why) instead of re-sending it unformatted, so the user
    // still gets the intended structure. a bot-authored notice goes through plain [sendText] instead.
    suspend fun sendReplyText(
        client: TelegramClient,
        chatId: Long,
        text: String,
        replyParameters: ReplyParameters?,
        formattingFileNotice: String
    ) {
        val html = text.withBrTagsAsNewlines()

        runCatching {
            sendTextMessage(client, chatId, html, ParseMode.HTML, replyParameters)
        }.recoverCatching { e ->
            if (e.isEntityParseError()) {
                log.warn { "Telegram rejected HTML, sending the reply as a $FALLBACK_DOCUMENT_FILENAME file" }
                sendTextAsDocument(client, chatId, html, formattingFileNotice, replyParameters)
            } else throw e
        }.getOrThrow()
    }

    // opt-in rich messages carry github-flavored markdown. if Telegram rejects the send, deliver the same
    // source as a .md document — clients render markdown inline and a document is not bound by the 4096-char
    // text limit. reply-not-found propagates so the caller can retry without the anchor.
    private suspend fun sendRichMessage(
        client: TelegramClient,
        chatId: Long,
        markdown: String,
        replyParameters: ReplyParameters?
    ) {
        runCatching {
            client.api {
                execute(SendRichMessage(chatId.toString(), InputRichMessage(markdown), replyParameters))
            }
        }.recoverCatching { e ->
            e.rethrowIfCancellation()
            rethrowIfReplyNotFound(e, replyParameters)
            log.warn(e) { "sendRichMessage failed for chat=$chatId, resending as a $MARKDOWN_DOCUMENT_FILENAME file" }
            sendMarkdownDocument(client, chatId, markdown, replyParameters)
        }.getOrThrow()
    }

    private suspend fun sendMarkdownDocument(
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

    private suspend fun sendTextAsDocument(
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

    private suspend fun sendDocument(
        client: TelegramClient,
        chatId: Long,
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
                client,
                chatId,
                document.bytes,
                document.filename,
                caption,
                replyParameters,
                formattingFileNotice
            )
        },
        onFallback = captionTextFallback(client, chatId, caption, replyParameters)
    )

    private suspend fun sendAnimation(
        client: TelegramClient,
        chatId: Long,
        replyParameters: ReplyParameters?,
        animation: BotOutput.Animation,
        caption: String?,
        formattingFileNotice: String
    ) {
        // generated GIF (bytes): fall back to document so the animation still arrives.
        val bytes = animation.bytes

        if (bytes != null) {
            sendMediaWithDocumentFallback(
                client = client,
                chatId = chatId,
                replyParameters = replyParameters,
                mediaLabel = "sendAnimation",
                bytes = bytes,
                filename = animation.filename,
                caption = caption,
                formattingFileNotice = formattingFileNotice,
                onTextFallback = captionTextFallback(client, chatId, caption, replyParameters),
                send = {
                    sendWithCaptionHtmlFallback(client, chatId, caption, replyParameters, formattingFileNotice) { text, parseMode ->
                        sendAnimationFile(client, chatId, bytes.asInputFile(animation.filename), text, parseMode, replyParameters)
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
                sendWithCaptionHtmlFallback(client, chatId, caption, replyParameters, formattingFileNotice) { text, parseMode ->
                    sendAnimationFile(client, chatId, InputFile(url), text, parseMode, replyParameters)
                }
            },
            onFallback = captionTextFallback(client, chatId, caption, replyParameters)
        )
    }

    private suspend fun sendPhoto(
        client: TelegramClient,
        chatId: Long,
        replyParameters: ReplyParameters?,
        photo: BotOutput.Photo,
        caption: String?,
        formattingFileNotice: String
    ) {
        val send =
            suspend {
                sendWithCaptionHtmlFallback(client, chatId, caption, replyParameters, formattingFileNotice) { text, parseMode ->
                    client.api {
                        execute(
                            SendPhoto.builder()
                                .chatId(chatId)
                                .photo(photo.bytes.asInputFile(photo.filename))
                                .caption(text)
                                .parseMode(parseMode)
                                .replyParameters(replyParameters)
                                .build()
                        )
                    }
                }
            }

        if (!photo.fallbackToDocument) {
            sendOrFallback(
                chatId = chatId,
                replyParameters = replyParameters,
                failureMessage = "sendPhoto failed, document copy should be delivered separately",
                send = send,
                onFallback = captionTextFallback(client, chatId, caption, replyParameters)
            )

            return
        }

        sendMediaWithDocumentFallback(
            client = client,
            chatId = chatId,
            replyParameters = replyParameters,
            mediaLabel = "sendPhoto",
            bytes = photo.bytes,
            filename = photo.filename,
            caption = caption,
            formattingFileNotice = formattingFileNotice,
            onTextFallback = captionTextFallback(client, chatId, caption, replyParameters),
            send = send
        )
    }

    private suspend fun sendPhotoGroup(
        client: TelegramClient,
        chatId: Long,
        replyParameters: ReplyParameters?,
        group: BotOutput.PhotoGroup,
        formattingFileNotice: String
    ) = sendOrFallback(
        chatId = chatId,
        replyParameters = replyParameters,
        failureMessage = "sendPhotoGroup failed, falling back to individual photos",
        send = {
            val media = group.photos.map {
                InputMediaPhoto.builder().media(ByteArrayInputStream(it.bytes), it.filename).build()
            }
            sendMediaGroup(client, chatId, media, replyParameters)
        },
        onFallback = {
            group.photos.forEach { photo ->
                runCatching { sendPhoto(client, chatId, replyParameters, photo, caption = null, formattingFileNotice) }
                    .onFailure { ie ->
                        ie.rethrowIfCancellation()
                        log.warn(ie) { "Fallback sendPhoto failed for chat=$chatId" }
                    }
            }
        }
    )

    private suspend fun sendDocumentGroup(
        client: TelegramClient,
        chatId: Long,
        replyParameters: ReplyParameters?,
        group: BotOutput.DocumentGroup,
        formattingFileNotice: String
    ) = sendOrFallback(
        chatId = chatId,
        replyParameters = replyParameters,
        failureMessage = "sendDocumentGroup failed, falling back to individual documents",
        send = {
            val media = group.documents.map {
                InputMediaDocument.builder().media(ByteArrayInputStream(it.bytes), it.filename).build()
            }
            sendMediaGroup(client, chatId, media, replyParameters)
        },
        onFallback = {
            group.documents.forEach { document ->
                runCatching { sendDocument(client, chatId, replyParameters, document, caption = null, formattingFileNotice) }
                    .onFailure { ie ->
                        ie.rethrowIfCancellation()
                        log.warn(ie) { "Fallback sendDocument failed for chat=$chatId" }
                    }
            }
        }
    )

    private suspend fun sendAudio(
        client: TelegramClient,
        chatId: Long,
        replyParameters: ReplyParameters?,
        audio: BotOutput.Audio,
        caption: String?,
        formattingFileNotice: String
    ) {
        val fullCaption = captionWithSourceLink(caption, audio.trackUrl)

        sendOrFallback(
            chatId = chatId,
            replyParameters = replyParameters,
            failureMessage = "sendAudio failed, falling back to text",
            send = {
                sendWithCaptionHtmlFallback(client, chatId, fullCaption, replyParameters, formattingFileNotice) { text, parseMode ->
                    client.api {
                        execute(
                            SendAudio.builder()
                                .chatId(chatId)
                                .audio(audio.bytes.asInputFile(audio.filename))
                                .title(audio.title)
                                .performer(audio.performer)
                                .duration(audio.durationSeconds)
                                .caption(text)
                                .parseMode(parseMode)
                                .replyParameters(replyParameters)
                                .build()
                        )
                    }
                }
            },
            onFallback = {
                val fallback = listOfNotNull(fullCaption, "${audio.title} — ${audio.performer}").joinToString("\n")
                sendText(client, chatId, fallback, replyParameters)
            }
        )
    }

    private suspend fun sendVoice(
        client: TelegramClient,
        chatId: Long,
        replyParameters: ReplyParameters?,
        voice: BotOutput.Voice,
        caption: String?,
        formattingFileNotice: String
    ) {
        sendOrFallback(
            chatId = chatId,
            replyParameters = replyParameters,
            failureMessage = "sendVoice failed, falling back to text",
            send = {
                sendWithCaptionHtmlFallback(client, chatId, caption, replyParameters, formattingFileNotice) { text, parseMode ->
                    client.api {
                        execute(
                            SendVoice.builder()
                                .chatId(chatId)
                                .voice(voice.bytes.asInputFile("voice.mp3"))
                                .duration(voice.durationSeconds)
                                .caption(text)
                                .parseMode(parseMode)
                                .replyParameters(replyParameters)
                                .build()
                        )
                    }
                }
            },
            onFallback = captionTextFallback(client, chatId, caption, replyParameters)
        )
    }

    private suspend fun sendVideo(
        client: TelegramClient,
        chatId: Long,
        replyParameters: ReplyParameters?,
        video: BotOutput.Video,
        caption: String?,
        formattingFileNotice: String
    ) {
        val fullCaption = captionWithSourceLink(caption, video.sourceUrl)
        val thumbnail = video.thumbnail

        sendMediaWithDocumentFallback(
            client = client,
            chatId = chatId,
            replyParameters = replyParameters,
            mediaLabel = "sendVideo",
            bytes = video.bytes,
            filename = video.filename,
            caption = fullCaption,
            formattingFileNotice = formattingFileNotice,
            onTextFallback = captionTextFallback(client, chatId, fullCaption, replyParameters),
            send = {
                sendWithCaptionHtmlFallback(client, chatId, fullCaption, replyParameters, formattingFileNotice) { text, parseMode ->
                    client.api {
                        execute(
                            SendVideo.builder()
                                .chatId(chatId)
                                .video(video.bytes.asInputFile(video.filename))
                                .thumbnail(thumbnail?.asInputFile(VIDEO_THUMBNAIL_FILENAME))
                                .cover(thumbnail?.asInputFile(VIDEO_COVER_FILENAME))
                                .caption(text)
                                .parseMode(parseMode)
                                .duration(video.durationSeconds)
                                .width(video.width)
                                .height(video.height)
                                .supportsStreaming(true)
                                .replyParameters(replyParameters)
                                .build()
                        )
                    }
                }
            }
        )
    }

    private suspend fun sendVideoNote(
        client: TelegramClient,
        chatId: Long,
        replyParameters: ReplyParameters?,
        videoNote: BotOutput.VideoNote,
        formattingFileNotice: String
    ) {
        sendMediaWithDocumentFallback(
            client = client,
            chatId = chatId,
            replyParameters = replyParameters,
            mediaLabel = "sendVideoNote",
            bytes = videoNote.bytes,
            filename = "video-note.mp4",
            caption = null,
            formattingFileNotice = formattingFileNotice,
            send = {
                client.api {
                    execute(
                        SendVideoNote.builder()
                            .chatId(chatId)
                            .videoNote(videoNote.bytes.asInputFile("video-note.mp4"))
                            .duration(videoNote.durationSeconds)
                            .length(videoNote.size)
                            .replyParameters(replyParameters)
                            .build()
                    )
                }
            }
        )
    }

    private suspend fun sendQuiz(
        client: TelegramClient,
        chatId: Long,
        replyParameters: ReplyParameters?,
        quiz: BotOutput.Quiz
    ) = sendOrFallback(
        chatId = chatId,
        replyParameters = replyParameters,
        failureMessage = "sendQuiz failed",
        send = {
            client.api {
                execute(
                    SendPoll.builder()
                        .chatId(chatId)
                        .question(quiz.question)
                        .options(quiz.options.map(::InputPollOption))
                        .type("quiz")
                        .correctOptionIds(listOf(quiz.correctOptionIndex))
                        .explanation(quiz.explanation)
                        .isAnonymous(quiz.isAnonymous)
                        .replyParameters(replyParameters)
                        .build()
                )
            }
        }
    )

    private suspend fun sendPoll(
        client: TelegramClient,
        chatId: Long,
        replyParameters: ReplyParameters?,
        poll: BotOutput.Poll
    ) = sendOrFallback(
        chatId = chatId,
        replyParameters = replyParameters,
        failureMessage = "sendPoll failed",
        send = {
            client.api {
                execute(
                    SendPoll.builder()
                        .chatId(chatId)
                        .question(poll.question)
                        .options(poll.options.map(::InputPollOption))
                        .type("regular")
                        .isAnonymous(poll.isAnonymous)
                        .allowMultipleAnswers(poll.allowsMultipleAnswers)
                        .replyParameters(replyParameters)
                        .build()
                )
            }
        }
    )

    private suspend fun sendDocumentWithCaptionFallback(
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

    private suspend fun sendWithHtmlFallback(send: suspend (parseMode: String?) -> Unit) {
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
    // same as [sendReplyText].
    private suspend fun sendWithCaptionHtmlFallback(
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

    private suspend fun sendMediaWithDocumentFallback(
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

    private suspend fun sendOrFallback(
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

    // deliver the caption as a plain message when the media itself could not be sent at all.
    private fun captionTextFallback(
        client: TelegramClient,
        chatId: Long,
        caption: String?,
        replyParameters: ReplyParameters?
    ): suspend () -> Unit =
        { caption?.let { sendText(client, chatId, it, replyParameters) } }

    private fun rethrowIfReplyNotFound(error: Throwable, replyParameters: ReplyParameters?) {
        if (replyParameters != null && error.isReplyMessageNotFound()) throw error
    }
}

// low-level builders live outside the object so its send/fallback policy stays readable at one altitude.

private suspend fun sendTextMessage(
    client: TelegramClient,
    chatId: Long,
    text: String,
    parseMode: String?,
    replyParameters: ReplyParameters?
) {
    client.api {
        execute(
            SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode(parseMode)
                .replyParameters(replyParameters)
                .build()
        )
    }
}

private suspend fun sendDocumentFile(
    client: TelegramClient,
    chatId: Long,
    bytes: ByteArray,
    filename: String,
    caption: String?,
    parseMode: String?,
    replyParameters: ReplyParameters?
) {
    client.api {
        execute(
            SendDocument.builder()
                .chatId(chatId)
                .document(bytes.asInputFile(filename))
                .caption(caption)
                .parseMode(parseMode)
                .replyParameters(replyParameters)
                .build()
        )
    }
}

private suspend fun sendAnimationFile(
    client: TelegramClient,
    chatId: Long,
    animation: InputFile,
    caption: String?,
    parseMode: String?,
    replyParameters: ReplyParameters?
) {
    client.api {
        execute(
            SendAnimation.builder()
                .chatId(chatId)
                .animation(animation)
                .caption(caption)
                .parseMode(parseMode)
                .replyParameters(replyParameters)
                .build()
        )
    }
}

private suspend fun sendMediaGroup(
    client: TelegramClient,
    chatId: Long,
    media: List<InputMedia>,
    replyParameters: ReplyParameters?
) {
    client.api {
        execute(
            SendMediaGroup.builder()
                .chatId(chatId)
                .medias(media)
                .replyParameters(replyParameters)
                .build()
        )
    }
}

// a fresh input stream per attempt: the previous attempt may have consumed the old one.
private fun ByteArray.asInputFile(filename: String): InputFile =
    InputFile(ByteArrayInputStream(this), filename)

/** Appends an HTML source link (e.g. `<a href="url">YouTube</a>`) to the caption; `null` when both are empty. */
private fun captionWithSourceLink(caption: String?, sourceUrl: String?): String? {
    val link = sourceUrl?.let { """<a href="${it.escapeHtml()}">${trackLinkLabel(it)}</a>""" }
    return listOfNotNull(caption, link).joinToString("\n").ifBlank { null }
}

private fun String.escapeHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

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
