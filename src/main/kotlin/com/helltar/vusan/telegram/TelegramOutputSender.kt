package com.helltar.vusan.telegram

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.outbox.BotOutput
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.ReplyMessageNotFoundException
import dev.inmo.tgbotapi.bot.exceptions.RequestException
import dev.inmo.tgbotapi.extensions.api.send.media.*
import dev.inmo.tgbotapi.extensions.api.send.polls.sendQuizPoll
import dev.inmo.tgbotapi.extensions.api.send.polls.sendRegularPoll
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.api.send.setMessageReaction
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import dev.inmo.tgbotapi.requests.abstracts.toInputFile
import dev.inmo.tgbotapi.types.ChatIdentifier
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.ReplyParameters
import dev.inmo.tgbotapi.types.media.TelegramMediaDocument
import dev.inmo.tgbotapi.types.media.TelegramMediaPhoto
import dev.inmo.tgbotapi.types.message.MarkdownParseMode
import dev.inmo.tgbotapi.types.message.ParseMode
import dev.inmo.tgbotapi.types.polls.InputPollOption
import io.github.oshai.kotlinlogging.KotlinLogging

internal object TelegramOutputSender {

    private const val MARKDOWN_FALLBACK_FILENAME = "message.md"

    private val log = KotlinLogging.logger {}

    suspend fun send(
        bot: TelegramBot,
        item: BotOutput,
        chatId: ChatIdentifier,
        replyParameters: ReplyParameters?,
        caption: String?,
        markdownFileNotice: String
    ) {
        when (item) {
            is BotOutput.Text -> sendReplyText(bot, chatId, item.text, replyParameters, markdownFileNotice)
            is BotOutput.Animation -> sendAnimation(bot, chatId, replyParameters, item, caption)
            is BotOutput.Photo -> sendPhoto(bot, chatId, replyParameters, item, caption)
            is BotOutput.PhotoGroup -> sendPhotoGroup(bot, chatId, replyParameters, item)
            is BotOutput.Document -> sendDocument(bot, chatId, replyParameters, item, caption)
            is BotOutput.DocumentGroup -> sendDocumentGroup(bot, chatId, replyParameters, item)
            is BotOutput.Audio -> sendAudio(bot, chatId, replyParameters, item, caption)
            is BotOutput.Voice -> sendVoice(bot, chatId, replyParameters, item, caption)
            is BotOutput.Video -> sendVideo(bot, chatId, replyParameters, item, caption)
            is BotOutput.VideoNote -> sendVideoNote(bot, chatId, replyParameters, item)
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
        sendWithMarkdownFallback { parseMode ->
            bot.sendTextMessage(
                chatId = chatId,
                text = text,
                parseMode = parseMode,
                replyParameters = replyParameters
            )
        }
    }

    // Agent reply text often carries malformed Markdown. When Telegram rejects it, deliver the raw text as a
    // `.md` document (with a short note explaining why) instead of re-sending it unformatted, so the user still
    // gets the intended structure. A bot-authored notice goes through plain [sendText] instead.
    suspend fun sendReplyText(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        text: String,
        replyParameters: ReplyParameters?,
        markdownFileNotice: String
    ) {
        runCatching {
            bot.sendTextMessage(chatId = chatId, text = text, parseMode = MarkdownParseMode, replyParameters = replyParameters)
        }.recoverCatching { e ->
            if (e is RequestException && e.isMarkdownError()) {
                log.warn { "Telegram rejected Markdown, sending the reply as a $MARKDOWN_FALLBACK_FILENAME file" }
                sendMarkdownAsDocument(bot, chatId, text, markdownFileNotice, replyParameters)
            } else throw e
        }.getOrThrow()
    }

    private suspend fun sendMarkdownAsDocument(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        text: String,
        notice: String,
        replyParameters: ReplyParameters?
    ) {
        runCatching {
            bot.sendDocument(
                chatId = chatId,
                document = text.encodeToByteArray().asMultipartFile(MARKDOWN_FALLBACK_FILENAME),
                text = notice,
                replyParameters = replyParameters
            )
        }.recoverCatching { e ->
            e.rethrowIfCancellation()
            log.warn(e) { "Markdown document fallback failed for chat=$chatId, sending plain text" }
            bot.sendTextMessage(chatId = chatId, text = text, parseMode = null, replyParameters = replyParameters)
        }.getOrThrow()
    }

    private suspend fun sendDocument(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        replyParameters: ReplyParameters?,
        document: BotOutput.Document,
        caption: String?
    ) = sendOrFallback(
        chatId = chatId,
        replyParameters = replyParameters,
        failureMessage = "sendDocument failed, falling back to text",
        send = {
            sendDocumentWithMarkdownFallback(
                bot,
                chatId,
                document.bytes,
                document.filename,
                caption,
                replyParameters
            )
        },
        onFallback = { caption?.let { sendText(bot, chatId, it, replyParameters) } }
    )

    private suspend fun sendAnimation(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        replyParameters: ReplyParameters?,
        animation: BotOutput.Animation,
        caption: String?
    ) {
        // Generated GIF (bytes): fall back to document so the animation still arrives.
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
                onTextFallback = { caption?.let { sendText(bot, chatId, it, replyParameters) } },
                send = {
                    sendWithMarkdownFallback { parseMode ->
                        bot.sendAnimation(
                            chatId = chatId,
                            animation = bytes.asMultipartFile(animation.filename),
                            text = caption,
                            parseMode = caption?.let { parseMode },
                            replyParameters = replyParameters
                        )
                    }
                }
            )

            return
        }

        // URL-based animation (e.g. Giphy).
        val url = requireNotNull(animation.url)

        sendOrFallback(
            chatId = chatId,
            replyParameters = replyParameters,
            failureMessage = "sendAnimation failed, falling back to text",
            send = {
                sendWithMarkdownFallback { parseMode ->
                    bot.sendAnimation(
                        chatId = chatId,
                        animation = url.toInputFile(),
                        text = caption,
                        parseMode = caption?.let { parseMode },
                        replyParameters = replyParameters
                    )
                }
            },
            onFallback = { caption?.let { sendText(bot, chatId, it, replyParameters) } }
        )
    }

    private suspend fun sendPhoto(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        replyParameters: ReplyParameters?,
        photo: BotOutput.Photo,
        caption: String?
    ) {
        val file = { photo.bytes.asMultipartFile(photo.filename) }

        val send =
            suspend {
                sendWithMarkdownFallback { parseMode ->
                    bot.sendPhoto(
                        chatId = chatId,
                        fileId = file(),
                        text = caption,
                        parseMode = caption?.let { parseMode },
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
                onFallback = { caption?.let { sendText(bot, chatId, it, replyParameters) } }
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
            onTextFallback = { caption?.let { sendText(bot, chatId, it, replyParameters) } },
            send = send
        )
    }

    private suspend fun sendPhotoGroup(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        replyParameters: ReplyParameters?,
        group: BotOutput.PhotoGroup
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
                runCatching { sendPhoto(bot, chatId, replyParameters, photo, caption = null) }
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
        group: BotOutput.DocumentGroup
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
                runCatching { sendDocument(bot, chatId, replyParameters, document, caption = null) }
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
        caption: String?
    ) {
        val fullCaption = buildAudioCaption(audio, caption)
        val audioFile = { audio.bytes.asMultipartFile(audio.filename) }

        sendOrFallback(
            chatId = chatId,
            replyParameters = replyParameters,
            failureMessage = "sendAudio failed, falling back to text",
            send = {
                sendWithMarkdownFallback { parseMode ->
                    bot.sendAudio(
                        chatId = chatId,
                        audio = audioFile(),
                        title = audio.title,
                        performer = audio.performer,
                        duration = audio.durationSeconds?.toLong(),
                        text = fullCaption,
                        parseMode = fullCaption?.let { parseMode },
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
        caption: String?
    ) {
        val file = { voice.bytes.asMultipartFile("voice.mp3") }

        sendOrFallback(
            chatId = chatId,
            replyParameters = replyParameters,
            failureMessage = "sendVoice failed, falling back to text",
            send = {
                sendWithMarkdownFallback { parseMode ->
                    bot.sendVoice(
                        chatId = chatId,
                        voice = file(),
                        duration = voice.durationSeconds?.toLong(),
                        text = caption,
                        parseMode = caption?.let { parseMode },
                        replyParameters = replyParameters
                    )
                }
            },
            onFallback = { caption?.let { sendText(bot, chatId, it, replyParameters) } }
        )
    }

    private suspend fun sendVideo(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        replyParameters: ReplyParameters?,
        video: BotOutput.Video,
        caption: String?
    ) {
        val fullCaption = buildVideoCaption(video, caption)
        val file = { video.bytes.asMultipartFile(video.filename) }

        sendMediaWithDocumentFallback(
            bot = bot,
            chatId = chatId,
            replyParameters = replyParameters,
            mediaLabel = "sendVideo",
            bytes = video.bytes,
            filename = video.filename,
            caption = fullCaption,
            onTextFallback = { fullCaption?.let { sendText(bot, chatId, it, replyParameters) } },
            send = {
                sendWithMarkdownFallback { parseMode ->
                    bot.sendVideo(
                        chatId = chatId,
                        video = file(),
                        text = fullCaption,
                        parseMode = fullCaption?.let { parseMode },
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
        videoNote: BotOutput.VideoNote
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

    private suspend fun sendDocumentWithMarkdownFallback(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        bytes: ByteArray,
        filename: String,
        caption: String?,
        replyParameters: ReplyParameters?
    ) {
        val file = { bytes.asMultipartFile(filename) }

        sendWithMarkdownFallback { parseMode ->
            bot.sendDocument(
                chatId = chatId,
                document = file(),
                text = caption,
                parseMode = caption?.let { parseMode },
                replyParameters = replyParameters
            )
        }
    }

    private suspend fun sendWithMarkdownFallback(send: suspend (parseMode: ParseMode?) -> Unit) {
        runCatching { send(MarkdownParseMode) }
            .recoverCatching { e ->
                if (e is RequestException && e.isMarkdownError()) {
                    log.warn { "Telegram rejected Markdown, retrying as plain text" }
                    send(null)
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
        send: suspend () -> Unit,
        onTextFallback: suspend () -> Unit = {}
    ) {
        runCatching { send() }
            .recoverCatching { e ->
                e.rethrowIfCancellation()
                rethrowIfReplyNotFound(e, replyParameters)
                log.warn(e) { "$mediaLabel failed for chat=$chatId, retrying as document" }
                sendDocumentWithMarkdownFallback(bot, chatId, bytes, filename, caption, replyParameters)
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

    private fun rethrowIfReplyNotFound(error: Throwable, replyParameters: ReplyParameters?) {
        if (replyParameters != null && error is ReplyMessageNotFoundException) throw error
    }
}

private fun buildAudioCaption(audio: BotOutput.Audio, caption: String?): String? {
    val link = audio.trackUrl?.let { "[${trackLinkLabel(it)}]($it)" }
    return listOfNotNull(caption, link).joinToString("\n").ifBlank { null }
}

private fun buildVideoCaption(video: BotOutput.Video, caption: String?): String? {
    val link = video.sourceUrl?.let { "[${trackLinkLabel(it)}]($it)" }
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
