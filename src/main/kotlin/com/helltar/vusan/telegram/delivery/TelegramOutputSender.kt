package com.helltar.vusan.telegram.delivery

import com.helltar.vusan.common.escapeHtml
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.telegram.api
import com.helltar.vusan.telegram.callback.inlineChoiceKeyboard
import com.helltar.vusan.telegram.telegramMessageId
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.ByteArrayInputStream
import org.telegram.telegrambots.meta.api.methods.ParseMode
import org.telegram.telegrambots.meta.api.methods.polls.SendPoll
import org.telegram.telegrambots.meta.api.methods.reactions.SetMessageReaction
import org.telegram.telegrambots.meta.api.methods.send.SendAudio
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.methods.send.SendVideo
import org.telegram.telegrambots.meta.api.methods.send.SendVideoNote
import org.telegram.telegrambots.meta.api.methods.send.SendVoice
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.ReplyParameters
import org.telegram.telegrambots.meta.api.objects.media.InputMediaAudio
import org.telegram.telegrambots.meta.api.objects.media.InputMediaDocument
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.polls.input.InputPollOption
import org.telegram.telegrambots.meta.api.objects.reactions.ReactionTypeEmoji
import org.telegram.telegrambots.meta.generics.TelegramClient

/**
 * Send mechanics for one [BotOutput]: which Bot API call each output kind maps to, and which
 * fallback wraps it. The fallbacks themselves live in `TelegramSendFallbacks.kt` and the raw API
 * calls in `TelegramRequests.kt`; routing and reply anchoring stay in [TelegramDelivery].
 */
internal object TelegramOutputSender {

    private const val VIDEO_NOTE_FILENAME = "video-note.mp4"
    private const val VIDEO_THUMBNAIL_FILENAME = "thumbnail.jpg"
    private const val VIDEO_COVER_FILENAME = "cover.jpg"

    private val log = KotlinLogging.logger {}

    suspend fun send(
        client: TelegramClient,
        item: BotOutput,
        target: ChatTarget,
        replyParameters: ReplyParameters?,
        caption: String?,
        formattingFileNotice: String,
        // told the id of a poll Telegram actually created. only a sent poll has one, and only then is
        // there anything for a later `poll_answer` update to be matched against.
        onPollSent: (suspend (String) -> Unit)? = null
    ) {
        when (item) {
            is BotOutput.Text -> sendReplyText(client, target, item.text, replyParameters, formattingFileNotice)
            is BotOutput.InlineChoice -> sendInlineChoice(client, target, item, replyParameters)
            is BotOutput.RichMessage -> sendRichMessage(client, target, item.markdown, replyParameters)
            is BotOutput.Animation -> sendAnimation(client, target, replyParameters, item, caption, formattingFileNotice)
            is BotOutput.Photo -> sendPhoto(client, target, replyParameters, item, caption, formattingFileNotice)
            is BotOutput.PhotoGroup -> sendPhotoGroup(client, target, replyParameters, item, formattingFileNotice)
            is BotOutput.Document -> sendDocument(client, target, replyParameters, item, caption, formattingFileNotice)
            is BotOutput.DocumentGroup -> sendDocumentGroup(client, target, replyParameters, item, formattingFileNotice)
            is BotOutput.Audio -> sendAudio(client, target, replyParameters, item, caption, formattingFileNotice)
            is BotOutput.AudioGroup -> sendAudioGroup(client, target, replyParameters, item, formattingFileNotice)
            is BotOutput.Voice -> sendVoice(client, target, replyParameters, item, caption, formattingFileNotice)
            is BotOutput.Video -> sendVideo(client, target, replyParameters, item, caption, formattingFileNotice)
            is BotOutput.VideoNote -> sendVideoNote(client, target, replyParameters, item, formattingFileNotice)
            is BotOutput.Sticker -> sendStickerFile(client, target, item.fileId, replyParameters)
            is BotOutput.Quiz -> sendQuiz(client, target, replyParameters, item, onPollSent)
            is BotOutput.Poll -> sendPoll(client, target, replyParameters, item, onPollSent)
            is BotOutput.Reaction -> sendReaction(client, target, item)
        }
    }

    private suspend fun sendInlineChoice(
        client: TelegramClient,
        target: ChatTarget,
        choice: BotOutput.InlineChoice,
        replyParameters: ReplyParameters?
    ) {
        sendTextMessage(
            client = client,
            target = target,
            text = choice.question,
            parseMode = null,
            replyParameters = replyParameters,
            replyMarkup = inlineChoiceKeyboard(choice)
        )
    }

    suspend fun sendText(
        client: TelegramClient,
        target: ChatTarget,
        text: String,
        replyParameters: ReplyParameters?
    ) {
        val html = text.withBrTagsAsNewlines()

        sendWithHtmlFallback { parseMode ->
            sendTextMessage(client, target, html, parseMode, replyParameters)
        }
    }

    // agent reply text can carry malformed HTML the model produced. when Telegram rejects it, deliver the raw
    // text as a document (with a short note explaining why) instead of re-sending it unformatted, so the user
    // still gets the intended structure. a bot-authored notice goes through plain [sendText] instead.
    suspend fun sendReplyText(
        client: TelegramClient,
        target: ChatTarget,
        text: String,
        replyParameters: ReplyParameters?,
        formattingFileNotice: String
    ) {
        val html = text.withBrTagsAsNewlines()

        runCatching {
            sendTextMessage(client, target, html, ParseMode.HTML, replyParameters)
        }.recoverCatching { e ->
            if (e.isEntityParseError()) {
                log.warn { "Telegram rejected HTML, sending the reply as a $FALLBACK_DOCUMENT_FILENAME file" }
                sendTextAsDocument(client, target, html, formattingFileNotice, replyParameters)
            } else throw e
        }.getOrThrow()
    }

    // opt-in rich messages carry github-flavored markdown. if Telegram rejects the send, deliver the same
    // source as a .md document — clients render markdown inline and a document is not bound by the 4096-char
    // text limit. reply-not-found propagates so the caller can retry without the anchor.
    private suspend fun sendRichMessage(
        client: TelegramClient,
        target: ChatTarget,
        markdown: String,
        replyParameters: ReplyParameters?
    ) {
        runCatching {
            client.api {
                executeAsync(richMessageRequest(target, markdown, replyParameters))
            }
        }.recoverCatching { e ->
            e.rethrowIfCancellation()
            rethrowIfReplyNotFound(e, replyParameters)
            rethrowIfRateLimited(e)
            log.warn(e) { "sendRichMessage failed for chat=${target.chatId}, resending as a $MARKDOWN_DOCUMENT_FILENAME file" }
            sendMarkdownDocument(client, target, markdown, replyParameters)
        }.getOrThrow()
    }

    private suspend fun sendReaction(
        client: TelegramClient,
        target: ChatTarget,
        reaction: BotOutput.Reaction
    ) {
        runCatching {
            client.api {
                executeAsync(
                    SetMessageReaction.builder()
                        .chatId(target.chatId.toString())
                        .messageId(reaction.messageId.telegramMessageId.toInt())
                        .reactionTypes(listOf(ReactionTypeEmoji.builder().emoji(reaction.emoji).build()))
                        .build()
                )
            }
        }.onFailure { e ->
            e.rethrowIfCancellation()
            // a failed reaction never breaks a turn — except when it failed because the chat is gone or
            // because flood control asked for a wait, both of which the caller has to hear about.
            rethrowIfRateLimited(e)
            rethrowIfChatUnreachable(e)
            log.warn(e) {
                "setMessageReaction failed chat=${target.chatId} message=${reaction.messageId} emoji=[${reaction.emoji}]"
            }
        }
    }

    private suspend fun sendDocument(
        client: TelegramClient,
        target: ChatTarget,
        replyParameters: ReplyParameters?,
        document: BotOutput.Document,
        caption: String?,
        formattingFileNotice: String
    ) = sendOrFallback(
        target = target,
        replyParameters = replyParameters,
        failureMessage = "sendDocument failed, falling back to text",
        send = {
            sendDocumentWithCaptionFallback(
                client,
                target,
                document.bytes,
                document.filename,
                caption,
                replyParameters,
                formattingFileNotice
            )
        },
        onFallback = captionTextFallback(client, target, caption, replyParameters)
    )

    private suspend fun sendAnimation(
        client: TelegramClient,
        target: ChatTarget,
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
                target = target,
                replyParameters = replyParameters,
                mediaLabel = "sendAnimation",
                bytes = bytes,
                filename = animation.filename,
                caption = caption,
                formattingFileNotice = formattingFileNotice,
                onTextFallback = captionTextFallback(client, target, caption, replyParameters),
                send = {
                    sendWithCaptionHtmlFallback(client, target, caption, replyParameters, formattingFileNotice) { text, parseMode ->
                        sendAnimationFile(client, target, bytes.asInputFile(animation.filename), text, parseMode, replyParameters)
                    }
                }
            )

            return
        }

        // remote URL-based animation (e.g. Giphy).
        val url = requireNotNull(animation.url)

        sendOrFallback(
            target = target,
            replyParameters = replyParameters,
            failureMessage = "sendAnimation failed, falling back to text",
            send = {
                sendWithCaptionHtmlFallback(client, target, caption, replyParameters, formattingFileNotice) { text, parseMode ->
                    sendAnimationFile(client, target, InputFile(url), text, parseMode, replyParameters)
                }
            },
            onFallback = captionTextFallback(client, target, caption, replyParameters)
        )
    }

    private suspend fun sendPhoto(
        client: TelegramClient,
        target: ChatTarget,
        replyParameters: ReplyParameters?,
        photo: BotOutput.Photo,
        caption: String?,
        formattingFileNotice: String
    ) {
        val send =
            suspend {
                sendWithCaptionHtmlFallback(client, target, caption, replyParameters, formattingFileNotice) { text, parseMode ->
                    client.api<Message> {
                        executeAsync(
                            SendPhoto.builder()
                                .chatId(target.chatId)
                                .messageThreadId(target.messageThreadId)
                                .photo(photo.bytes.asInputFile(photo.filename))
                                .caption(text)
                                .parseMode(parseMode)
                                .replyParameters(replyParameters)
                                .build()
                        )
                    }
                }
            }

        sendMediaWithDocumentFallback(
            client = client,
            target = target,
            replyParameters = replyParameters,
            mediaLabel = "sendPhoto",
            bytes = photo.bytes,
            filename = photo.filename,
            caption = caption,
            formattingFileNotice = formattingFileNotice,
            onTextFallback = captionTextFallback(client, target, caption, replyParameters),
            send = send
        )
    }

    private suspend fun sendPhotoGroup(
        client: TelegramClient,
        target: ChatTarget,
        replyParameters: ReplyParameters?,
        group: BotOutput.PhotoGroup,
        formattingFileNotice: String
    ) = sendOrFallback(
        target = target,
        replyParameters = replyParameters,
        failureMessage = "sendPhotoGroup failed, falling back to individual photos",
        send = {
            val media = group.photos.map {
                InputMediaPhoto.builder().media(ByteArrayInputStream(it.bytes), it.filename).build()
            }
            sendMediaGroup(client, target, media, replyParameters)
        },
        onFallback = {
            group.photos.forEach { photo ->
                runCatching { sendPhoto(client, target, replyParameters, photo, caption = null, formattingFileNotice) }
                    .onFailure { ie ->
                        ie.rethrowIfCancellation()
                        log.warn(ie) { "Fallback sendPhoto failed for chat=${target.chatId}" }
                    }
            }
        }
    )

    private suspend fun sendDocumentGroup(
        client: TelegramClient,
        target: ChatTarget,
        replyParameters: ReplyParameters?,
        group: BotOutput.DocumentGroup,
        formattingFileNotice: String
    ) = sendOrFallback(
        target = target,
        replyParameters = replyParameters,
        failureMessage = "sendDocumentGroup failed, falling back to individual documents",
        send = {
            val media = group.documents.map {
                InputMediaDocument.builder().media(ByteArrayInputStream(it.bytes), it.filename).build()
            }
            sendMediaGroup(client, target, media, replyParameters)
        },
        onFallback = {
            group.documents.forEach { document ->
                runCatching { sendDocument(client, target, replyParameters, document, caption = null, formattingFileNotice) }
                    .onFailure { ie ->
                        ie.rethrowIfCancellation()
                        log.warn(ie) { "Fallback sendDocument failed for chat=${target.chatId}" }
                    }
            }
        }
    )

    private suspend fun sendAudioGroup(
        client: TelegramClient,
        target: ChatTarget,
        replyParameters: ReplyParameters?,
        group: BotOutput.AudioGroup,
        formattingFileNotice: String
    ) = sendOrFallback(
        target = target,
        replyParameters = replyParameters,
        failureMessage = "sendAudioGroup failed, falling back to individual tracks",
        send = {
            // the album carries what a single sendAudio would: player metadata per track, and the
            // source link as that track's own caption rather than one caption for the whole batch.
            val media = group.audios.map { audio ->
                InputMediaAudio.builder()
                    .media(ByteArrayInputStream(audio.bytes), audio.filename)
                    .title(audio.title)
                    .performer(audio.performer)
                    .duration(audio.durationSeconds)
                    .caption(captionWithSourceLink(caption = null, sourceUrl = audio.trackUrl))
                    .parseMode(ParseMode.HTML)
                    .build()
            }

            sendMediaGroup(client, target, media, replyParameters)
        },
        onFallback = {
            group.audios.forEach { audio ->
                runCatching { sendAudio(client, target, replyParameters, audio, caption = null, formattingFileNotice) }
                    .onFailure { ie ->
                        ie.rethrowIfCancellation()
                        log.warn(ie) { "Fallback sendAudio failed for chat=${target.chatId}" }
                    }
            }
        }
    )

    private suspend fun sendAudio(
        client: TelegramClient,
        target: ChatTarget,
        replyParameters: ReplyParameters?,
        audio: BotOutput.Audio,
        caption: String?,
        formattingFileNotice: String
    ) {
        val fullCaption = captionWithSourceLink(caption, audio.trackUrl)

        sendOrFallback(
            target = target,
            replyParameters = replyParameters,
            failureMessage = "sendAudio failed, falling back to text",
            send = {
                sendWithCaptionHtmlFallback(client, target, fullCaption, replyParameters, formattingFileNotice) { text, parseMode ->
                    client.api<Message> {
                        executeAsync(
                            SendAudio.builder()
                                .chatId(target.chatId)
                                .messageThreadId(target.messageThreadId)
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
                sendText(client, target, fallback, replyParameters)
            }
        )
    }

    private suspend fun sendVoice(
        client: TelegramClient,
        target: ChatTarget,
        replyParameters: ReplyParameters?,
        voice: BotOutput.Voice,
        caption: String?,
        formattingFileNotice: String
    ) {
        sendOrFallback(
            target = target,
            replyParameters = replyParameters,
            failureMessage = "sendVoice failed, falling back to text",
            send = {
                sendWithCaptionHtmlFallback(client, target, caption, replyParameters, formattingFileNotice) { text, parseMode ->
                    client.api<Message> {
                        executeAsync(
                            SendVoice.builder()
                                .chatId(target.chatId)
                                .messageThreadId(target.messageThreadId)
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
            onFallback = captionTextFallback(client, target, caption, replyParameters)
        )
    }

    private suspend fun sendVideo(
        client: TelegramClient,
        target: ChatTarget,
        replyParameters: ReplyParameters?,
        video: BotOutput.Video,
        caption: String?,
        formattingFileNotice: String
    ) {
        val fullCaption = captionWithSourceLink(caption, video.sourceUrl)
        val thumbnail = video.thumbnail

        sendMediaWithDocumentFallback(
            client = client,
            target = target,
            replyParameters = replyParameters,
            mediaLabel = "sendVideo",
            bytes = video.bytes,
            filename = video.filename,
            caption = fullCaption,
            formattingFileNotice = formattingFileNotice,
            onTextFallback = captionTextFallback(client, target, fullCaption, replyParameters),
            send = {
                sendWithCaptionHtmlFallback(client, target, fullCaption, replyParameters, formattingFileNotice) { text, parseMode ->
                    client.api<Message> {
                        executeAsync(
                            SendVideo.builder()
                                .chatId(target.chatId)
                                .messageThreadId(target.messageThreadId)
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
        target: ChatTarget,
        replyParameters: ReplyParameters?,
        videoNote: BotOutput.VideoNote,
        formattingFileNotice: String
    ) {
        runCatching {
            client.api<Message> {
                executeAsync(
                    SendVideoNote.builder()
                        .chatId(target.chatId)
                        .messageThreadId(target.messageThreadId)
                        .videoNote(videoNote.bytes.asInputFile(VIDEO_NOTE_FILENAME))
                        .duration(videoNote.durationSeconds)
                        .length(videoNote.size)
                        .replyParameters(replyParameters)
                        .build()
                )
            }
        }.recoverCatching { e ->
            e.rethrowIfCancellation()
            rethrowIfReplyNotFound(e, replyParameters)
            rethrowIfChatUnreachable(e)

            // a recipient can refuse voice and video messages from anyone outside their contacts, which
            // comes back as `VOICE_MESSAGES_FORBIDDEN`. the same mp4 is still allowed as an ordinary
            // video, and one that plays in the chat beats the document the generic fallback would attach.
            log.warn(e) { "sendVideoNote rejected for chat=${target.chatId}, sending the same mp4 as a video" }
            sendVideo(client, target, replyParameters, videoNote.asVideo(), caption = null, formattingFileNotice)
        }.getOrThrow()
    }

    private fun BotOutput.VideoNote.asVideo(): BotOutput.Video =
        BotOutput.Video(
            bytes = bytes,
            filename = VIDEO_NOTE_FILENAME,
            durationSeconds = durationSeconds,
            width = size,
            height = size
        )

    private suspend fun sendQuiz(
        client: TelegramClient,
        target: ChatTarget,
        replyParameters: ReplyParameters?,
        quiz: BotOutput.Quiz,
        onPollSent: (suspend (String) -> Unit)?
    ) = sendOrFallback(
        target = target,
        replyParameters = replyParameters,
        failureMessage = "sendQuiz failed",
        send = {
            client.api<Message> {
                executeAsync(
                    SendPoll.builder()
                        .chatId(target.chatId)
                        .messageThreadId(target.messageThreadId)
                        .question(quiz.question)
                        .options(quiz.options.map(::InputPollOption))
                        .type("quiz")
                        .correctOptionIds(listOf(quiz.correctOptionIndex))
                        .explanation(quiz.explanation)
                        .isAnonymous(quiz.isAnonymous)
                        .replyParameters(replyParameters)
                        .build()
                )
            }.reportPoll(onPollSent)
        }
    )

    private suspend fun sendPoll(
        client: TelegramClient,
        target: ChatTarget,
        replyParameters: ReplyParameters?,
        poll: BotOutput.Poll,
        onPollSent: (suspend (String) -> Unit)?
    ) = sendOrFallback(
        target = target,
        replyParameters = replyParameters,
        failureMessage = "sendPoll failed",
        send = {
            client.api<Message> {
                executeAsync(
                    SendPoll.builder()
                        .chatId(target.chatId)
                        .messageThreadId(target.messageThreadId)
                        .question(poll.question)
                        .options(poll.options.map(::InputPollOption))
                        .type("regular")
                        .isAnonymous(poll.isAnonymous)
                        .allowMultipleAnswers(poll.allowsMultipleAnswers)
                        .replyParameters(replyParameters)
                        .build()
                )
            }.reportPoll(onPollSent)
        }
    )

    // the id lives on the poll inside the sent message, and only a send that really produced one has
    // it: a fallback that turned the poll into text leaves nothing to match an answer against.
    private suspend fun Message.reportPoll(onPollSent: (suspend (String) -> Unit)?) {
        val id = poll?.id ?: return
        onPollSent?.invoke(id)
    }

    // deliver the caption as a plain message when the media itself could not be sent at all.
    private fun captionTextFallback(
        client: TelegramClient,
        target: ChatTarget,
        caption: String?,
        replyParameters: ReplyParameters?
    ): suspend () -> Unit =
        { caption?.let { sendText(client, target, it, replyParameters) } }
}

/** Appends an HTML source link (e.g. `<a href="url">YouTube</a>`) to the caption; `null` when both are empty. */
private fun captionWithSourceLink(caption: String?, sourceUrl: String?): String? {
    val link = sourceUrl?.let { """<a href="${it.escapeHtml()}">${trackLinkLabel(it)}</a>""" }
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
