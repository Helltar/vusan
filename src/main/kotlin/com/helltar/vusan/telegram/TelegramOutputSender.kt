package com.helltar.vusan.telegram

import com.helltar.vusan.outbox.BotOutput
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.ReplyMessageNotFoundException
import dev.inmo.tgbotapi.bot.exceptions.RequestException
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.api.send.setMessageReaction
import dev.inmo.tgbotapi.extensions.api.send.media.sendAnimation
import dev.inmo.tgbotapi.extensions.api.send.media.sendAudio
import dev.inmo.tgbotapi.extensions.api.send.media.sendDocument
import dev.inmo.tgbotapi.extensions.api.send.media.sendPhoto
import dev.inmo.tgbotapi.extensions.api.send.media.sendVideoNote
import dev.inmo.tgbotapi.extensions.api.send.media.sendVisualMediaGroup
import dev.inmo.tgbotapi.extensions.api.send.media.sendVoice
import dev.inmo.tgbotapi.extensions.api.send.polls.sendQuizPoll
import dev.inmo.tgbotapi.extensions.api.send.polls.sendRegularPoll
import dev.inmo.tgbotapi.requests.abstracts.asMultipartFile
import dev.inmo.tgbotapi.requests.abstracts.toInputFile
import dev.inmo.tgbotapi.types.ChatIdentifier
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.ReplyParameters
import dev.inmo.tgbotapi.types.media.TelegramMediaPhoto
import dev.inmo.tgbotapi.types.message.MarkdownParseMode
import dev.inmo.tgbotapi.types.message.ParseMode
import dev.inmo.tgbotapi.types.polls.InputPollOption
import io.github.oshai.kotlinlogging.KotlinLogging

internal object TelegramOutputSender {

    private val log = KotlinLogging.logger {}

    suspend fun send(
        bot: TelegramBot,
        item: BotOutput,
        chatId: ChatIdentifier,
        replyParameters: ReplyParameters?,
        caption: String?
    ) {
        when (item) {
            is BotOutput.Text -> sendText(bot, chatId, item.text, replyParameters)
            is BotOutput.Animation -> sendAnimation(bot, chatId, replyParameters, item, caption)
            is BotOutput.Photo -> sendPhoto(bot, chatId, replyParameters, item, caption)
            is BotOutput.PhotoGroup -> sendPhotoGroup(bot, chatId, replyParameters, item)
            is BotOutput.Document -> sendDocument(bot, chatId, replyParameters, item, caption)
            is BotOutput.Audio -> sendAudio(bot, chatId, replyParameters, item, caption)
            is BotOutput.Voice -> sendVoice(bot, chatId, replyParameters, item, caption)
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
            log.warn(e) { "setMessageReaction failed chat=$chatId message=${reaction.messageId} emoji=[${reaction.emoji}]" }
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
        send = { sendDocumentWithMarkdownFallback(bot, chatId, document.bytes, document.filename, caption, replyParameters) },
        onFallback = { caption?.let { sendText(bot, chatId, it, replyParameters) } }
    )

    private suspend fun sendAnimation(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        replyParameters: ReplyParameters?,
        animation: BotOutput.Animation,
        caption: String?
    ) = sendOrFallback(
        chatId = chatId,
        replyParameters = replyParameters,
        failureMessage = "sendAnimation failed, falling back to text",
        send = {
            sendWithMarkdownFallback { parseMode ->
                bot.sendAnimation(
                    chatId = chatId,
                    animation = animation.url.toInputFile(),
                    text = caption,
                    parseMode = caption?.let { parseMode },
                    replyParameters = replyParameters
                )
            }
        },
        onFallback = { caption?.let { sendText(bot, chatId, it, replyParameters) } }
    )

    private suspend fun sendPhoto(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        replyParameters: ReplyParameters?,
        photo: BotOutput.Photo,
        caption: String?
    ) {
        val file = { photo.bytes.asMultipartFile(photo.filename) }

        runCatching {
            sendWithMarkdownFallback { parseMode ->
                bot.sendPhoto(
                    chatId = chatId,
                    fileId = file(),
                    text = caption,
                    parseMode = caption?.let { parseMode },
                    replyParameters = replyParameters
                )
            }
        }.recoverCatching { e ->
            rethrowIfReplyNotFound(e, replyParameters)
            log.warn(e) { "sendPhoto failed for chat=$chatId, retrying as document" }
            sendDocumentWithMarkdownFallback(bot, chatId, photo.bytes, photo.filename, caption, replyParameters)
        }.onFailure { e ->
            rethrowIfReplyNotFound(e, replyParameters)
            log.warn(e) { "sendDocument fallback failed for chat=$chatId, falling back to text" }
            caption?.let { sendText(bot, chatId, it, replyParameters) }
        }
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
                    .onFailure { ie -> log.warn(ie) { "Fallback sendPhoto failed for chat=$chatId" } }
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

    private suspend fun sendVideoNote(
        bot: TelegramBot,
        chatId: ChatIdentifier,
        replyParameters: ReplyParameters?,
        videoNote: BotOutput.VideoNote
    ) {
        val file = { videoNote.bytes.asMultipartFile("video-note.mp4") }

        runCatching {
            bot.sendVideoNote(
                chatId = chatId,
                videoNote = file(),
                duration = videoNote.durationSeconds?.toLong(),
                size = videoNote.size,
                replyParameters = replyParameters
            )
        }.recoverCatching { e ->
            rethrowIfReplyNotFound(e, replyParameters)
            log.warn(e) { "sendVideoNote failed for chat=$chatId, retrying as document" }
            sendDocumentWithMarkdownFallback(bot, chatId, videoNote.bytes, "video-note.mp4", caption = null, replyParameters)
        }.onFailure { e ->
            rethrowIfReplyNotFound(e, replyParameters)
            log.warn(e) { "sendVideoNote document fallback failed for chat=$chatId" }
        }
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

    private suspend fun sendOrFallback(
        chatId: ChatIdentifier,
        replyParameters: ReplyParameters?,
        failureMessage: String,
        send: suspend () -> Unit,
        onFallback: suspend () -> Unit = {}
    ) {
        runCatching { send() }.onFailure { e ->
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
