package com.helltar.vusan.tools.imagegen

import com.helltar.vusan.common.isEffectivelyBlank
import kotlin.io.path.Path
import kotlin.io.path.isReadable
import kotlin.io.path.name
import kotlin.io.path.readBytes

/**
 * What a picture of the bot itself is built from.
 *
 * Text-to-image invents a face per call, so a selfie from the prompt alone shows a different person each
 * time. A [reference] makes the request an *edit* of that photo, which holds the face still. [appearance]
 * carries what a head-and-shoulders photo cannot show (height, build, tattoos, usual clothes) and is all that
 * is left without a reference.
 */
class SelfImage(val reference: SourceImage?, val appearance: String?)

/**
 * Reads the reference photo once at startup: the operator's file when set, otherwise what [loadAvatar] finds,
 * the bot's own platform picture, which needs no configuration and is the face people already see. A
 * deployment with neither still works on [appearance] alone.
 */
internal suspend fun resolveSelfImage(
    file: String?,
    appearance: String?,
    loadAvatar: suspend () -> SourceImage? = { null },
): SelfImage? {
    val reference = file?.let { readReferenceFile(it) } ?: loadAvatar()
    val notes = appearance?.takeUnless { it.isEffectivelyBlank() }

    // with neither nothing holds a face still, which the startup summary says
    return if (reference == null && notes == null) null else SelfImage(reference, notes)
}

/**
 * The instruction that renders [scene] as the character in the reference photo.
 *
 * The edit endpoint reads a prompt as "keep this picture, change what I named", so the wrapper says the
 * opposite: identity stays, the frame goes. Otherwise the result is the avatar with a hat on. "Face" must be
 * split from "head angle and expression" just as explicitly, or every picture repeats the reference's
 * straight-on stare on a new body.
 */
internal fun selfPortraitPrompt(scene: String, appearance: String?): String =
    buildString {
        append("Draw a completely new photo of the person in the reference image. ")
        append("Treat the reference as a likeness sheet rather than a frame to copy: keep their face shape, features, hair, and build, unmistakably the same person, and take nothing else from it. ")
        append("Head angle, gaze, expression, pose, framing, clothing, lighting, and background all come from the description below, never from the reference.")
        appearance?.let { append("\n\n$it") }
        append("\n\n$scene")
    }

/**
 * The same identity as one source among the user's own images. [selfPortraitPrompt] takes *nothing* but the
 * face from its reference, which is wrong once the other images are the scene being edited, hence a separate
 * text.
 */
internal fun selfInEditPrompt(instruction: String, appearance: String?, otherImages: Int): String =
    buildString {
        append("The first image is a likeness sheet for one person: keep their face shape, features, hair, and build, unmistakably the same person, and take nothing else from it. ")

        if (otherImages == 1)
            append("The second image is the picture being edited. ")
        else
            append("The other $otherImages images are the pictures being edited. ")

        append("Place that person into it as the instruction below says, and leave the rest of it as it is.")
        appearance?.let { append("\n\n$it") }
        append("\n\n$instruction")
    }

/** The same picture without a reference: the written description is the whole identity. */
internal fun String.withAppearance(appearance: String?): String =
    appearance?.let { "$it\n\n$this" } ?: this

private fun readReferenceFile(path: String): SourceImage {
    val file = Path(path)

    require(file.isReadable()) { "SELF_IMAGE_FILE=[$path] does not exist or is not readable" }

    val contentType =
        requireNotNull(imageContentTypeOrNull(file.name)) {
            "SELF_IMAGE_FILE=[$path] must be a PNG, JPEG, or WebP image"
        }

    val bytes = file.readBytes()

    require(bytes.isNotEmpty()) { "SELF_IMAGE_FILE=[$path] is empty" }

    return SourceImage(bytes, file.name, contentType)
}
