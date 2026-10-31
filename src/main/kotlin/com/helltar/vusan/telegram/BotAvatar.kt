package com.helltar.vusan.telegram

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.tools.imagegen.SourceImage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.telegram.telegrambots.meta.api.methods.GetUserProfilePhotos
import org.telegram.telegrambots.meta.generics.TelegramClient

private val log = KotlinLogging.logger("BotAvatar")

// telegram stores profile photos as jpeg capped at 640x640, small for a reference but enough to fix the face;
// SELF_IMAGE_FILE is for deployments with the original.
private const val TELEGRAM_PHOTO_NAME = "avatar.jpg"

/**
 * The bot's own profile photo as a reference for the self-portrait tools, read as anyone's avatar would be;
 * nothing else in the Bot API exposes it. A failure is not fatal: the character then rests on
 * `APPEARANCE_FILE` alone.
 */
internal suspend fun TelegramClient.profilePhotoReference(botId: Long): SourceImage? =
    runCatching {
        api { executeAsync(GetUserProfilePhotos.builder().userId(botId).limit(1).build()) }
            .photos
            .firstOrNull()
            ?.maxByOrNull { it.width }
            ?.let { SourceImage(downloadFileBytes(it.fileId), TELEGRAM_PHOTO_NAME, "image/jpeg") }
            ?.also { log.info { "Self-portrait reference: Telegram profile photo (${it.bytes.size} bytes)" } }
    }
        .onFailure { e ->
            e.rethrowIfCancellation()
            log.warn(e) { "Reading the bot's Telegram profile photo failed" }
        }
        .getOrNull()
