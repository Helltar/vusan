package com.helltar.vusan.telegram

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.tools.imagegen.SourceImage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.telegram.telegrambots.meta.api.methods.GetUserProfilePhotos
import org.telegram.telegrambots.meta.generics.TelegramClient

private val log = KotlinLogging.logger("BotAvatar")

// telegram stores a profile photo as jpeg and caps it at 640x640, which is small for a reference but
// still fixes the face; SELF_IMAGE_FILE exists for deployments that have the original.
private const val TELEGRAM_PHOTO_NAME = "avatar.jpg"

/**
 * The bot's own profile photo, as a reference the self-portrait tools can edit.
 *
 * A bot reads its own avatar the way it reads anyone's; nothing else in the Bot API exposes it. A
 * failure here is not fatal — the character then rests on `APPEARANCE` alone.
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
