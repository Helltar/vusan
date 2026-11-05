package com.helltar.vusan.tools.imagegen

import com.helltar.vusan.common.mimeTypeOfName
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind

/** The pictures one edit or animation is drawn from, or the answer saying why there are none. */
internal sealed interface EditSources {

    /** [images] in the order they are sent; [used] of the [offered] editable user pictures made it in. */
    class Ready(val images: List<SourceImage>, val used: Int, val offered: Int) : EditSources

    class Refused(val answer: String) : EditSources
}

/**
 * Loads the images among [candidates] for one request of at most [max] sources, [self] first: the model holds
 * the first source closest to the original, so a picture of the bot puts its face there and the user's
 * images after it.
 */
internal suspend fun collectEditSources(candidates: List<AttachedFile>, self: SourceImage?, max: Int): EditSources {
    val pictures = candidates.filter { it.kind == AttachedFileKind.IMAGE }

    if (pictures.isEmpty())
        return EditSources.Refused(
            candidates.firstOrNull()
                ?.let { "`${it.name}` is not an image, so it can't be edited." }
                ?: "No image is attached in this turn — ask the user to send or reply to one, or name one.",
        )

    val editable = pictures.mapNotNull { file -> file.editContentTypeOrNull()?.let { file to it } }

    if (editable.isEmpty())
        return EditSources.Refused(
            "`${pictures.first().name}` is not a supported image type for editing — use a PNG, JPEG, or WebP image.",
        )

    val sources = listOfNotNull(self).toMutableList()
    var uploadBytes = self?.bytes?.size ?: 0
    val taken = editable.take(max - sources.size)

    for ((file, contentType) in taken) {
        file.fileSizeBytes?.let {
            if (it > MAX_EDIT_IMAGE_BYTES)
                return EditSources.Refused("`${file.name}` is too large to edit ($it bytes, limit $MAX_EDIT_IMAGE_BYTES).")
        }

        val bytes = file.loadBytes()

        if (bytes.size > MAX_EDIT_IMAGE_BYTES)
            return EditSources.Refused("`${file.name}` is too large to edit (${bytes.size} bytes, limit $MAX_EDIT_IMAGE_BYTES).")

        uploadBytes += bytes.size

        if (uploadBytes > MAX_EDIT_TOTAL_BYTES)
            return EditSources.Refused(
                "The images add up to more than $MAX_EDIT_TOTAL_BYTES bytes — ask the user which of them the edit is about.",
            )

        sources += SourceImage(bytes, file.name, contentType)
    }

    return EditSources.Ready(sources, used = taken.size, offered = editable.size)
}

/** What a result adds when the provider took fewer of the user's images than were offered. */
internal fun EditSources.Ready.leftOut(max: Int): String =
    if (used < offered)
        " The provider edits at most $max images at once, so only the first $used of the $offered were used; " +
                "tell the user if that matters."
    else
        ""

internal const val MAX_EDIT_IMAGE_BYTES = 25 * 1024 * 1024

// per upload, not per image: ten album photos at the per-image limit would be a quarter-gigabyte request
// spending its five-minute timeout uploading.
internal const val MAX_EDIT_TOTAL_BYTES = 45 * 1024 * 1024

private val SUPPORTED_EDIT_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp")

private fun AttachedFile.editContentTypeOrNull(): String? {
    mimeType?.lowercase()?.let { if (it in SUPPORTED_EDIT_MIME_TYPES) return it }

    return imageContentTypeOrNull(name)
}

/** The image types both edit routes accept, read off a filename when nothing else declares one. */
internal fun imageContentTypeOrNull(filename: String): String? =
    mimeTypeOfName(filename)?.takeIf { it in SUPPORTED_EDIT_MIME_TYPES }
