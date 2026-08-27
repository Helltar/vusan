package com.helltar.vusan.tools.imagegen

import com.helltar.vusan.common.isEffectivelyBlank
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.telegram.api
import com.helltar.vusan.telegram.downloadFileBytes
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.io.path.Path
import kotlin.io.path.isReadable
import kotlin.io.path.name
import kotlin.io.path.readBytes
import org.telegram.telegrambots.meta.api.methods.GetUserProfilePhotos
import org.telegram.telegrambots.meta.generics.TelegramClient

private val log = KotlinLogging.logger("SelfImage")

// telegram stores a profile photo as jpeg and caps it at 640x640, which is small for a reference but
// still fixes the face; SELF_IMAGE_FILE exists for deployments that have the original.
private const val TELEGRAM_PHOTO_NAME = "avatar.jpg"

/** A picture of the character, handed to the image model as the identity it has to keep. */
class ReferenceImage(val bytes: ByteArray, val filename: String, val contentType: String)

/**
 * What a picture of the bot itself is built from.
 *
 * Text-to-image invents a face per call, so "send a selfie" drawn from the prompt alone shows a
 * different person every time. A [reference] turns the request into an *edit* of that photo instead,
 * which is what holds the face still across pictures. [appearance] carries what a head-and-shoulders
 * reference cannot show — height, build, tattoos, what the character usually wears — and is the only
 * thing left when no reference exists.
 */
class SelfImage(val reference: ReferenceImage?, val appearance: String?)

/**
 * Read the character's reference photo once at startup: the operator's file when set, otherwise the
 * bot's own Telegram avatar, which needs no configuration and is already the face people see next to
 * every message it sends.
 */
internal suspend fun resolveSelfImage(
    file: String?,
    appearance: String?,
    client: TelegramClient,
    botId: Long
): SelfImage? {
    val reference = file?.let { readReferenceFile(it) } ?: client.profilePhotoReference(botId)
    val notes = appearance?.takeUnless { it.isEffectivelyBlank() }

    if (reference == null && notes == null) {
        log.warn {
            "Self-portraits: no reference photo and no APPEARANCE — a picture of the bot itself " +
                    "shows a different person every time"
        }

        return null
    }

    return SelfImage(reference, notes)
}

/**
 * Compose the instruction that renders [scene] as a picture of the character in the reference photo.
 *
 * The edit endpoint's default reading of a prompt is "keep this picture, change what I named", so the
 * wrapper spells out the opposite: identity stays, the frame around it is thrown away. Without that
 * the result is the avatar with a hat on rather than the scene that was asked for.
 */
internal fun selfPortraitPrompt(scene: String, appearance: String?): String =
    buildString {
        append("Draw a completely new picture of the person in the reference image. ")
        append("Keep their face, hair, and build exactly as in the reference, unmistakably the same person. ")
        append("Take nothing else from it — pose, framing, clothing, lighting, and background all come from the description below.")
        appearance?.let { append("\n\n$it") }
        append("\n\n$scene")
    }

/** The same picture asked for without a reference: the written description is all the identity there is. */
internal fun String.withAppearance(appearance: String?): String =
    appearance?.let { "$it\n\n$this" } ?: this

private fun readReferenceFile(path: String): ReferenceImage {
    val file = Path(path)

    require(file.isReadable()) { "SELF_IMAGE_FILE=[$path] does not exist or is not readable" }

    val contentType =
        requireNotNull(imageContentTypeOrNull(file.name)) {
            "SELF_IMAGE_FILE=[$path] must be a PNG, JPEG, or WebP image"
        }

    val bytes = file.readBytes()

    require(bytes.isNotEmpty()) { "SELF_IMAGE_FILE=[$path] is empty" }

    log.info { "Self-portrait reference: SELF_IMAGE_FILE=[$path] (${bytes.size} bytes)" }

    return ReferenceImage(bytes, file.name, contentType)
}

// a bot reads its own avatar the way it reads anyone's; nothing else in the Bot API exposes it.
private suspend fun TelegramClient.profilePhotoReference(botId: Long): ReferenceImage? =
    runCatching {
        api { executeAsync(GetUserProfilePhotos.builder().userId(botId).limit(1).build()) }
            .photos
            .firstOrNull()
            ?.maxByOrNull { it.width }
            ?.let { ReferenceImage(downloadFileBytes(it.fileId), TELEGRAM_PHOTO_NAME, "image/jpeg") }
            ?.also { log.info { "Self-portrait reference: Telegram profile photo (${it.bytes.size} bytes)" } }
    }
        .onFailure { e ->
            e.rethrowIfCancellation()
            log.warn(e) { "Reading the bot's Telegram profile photo failed" }
        }
        .getOrNull()
