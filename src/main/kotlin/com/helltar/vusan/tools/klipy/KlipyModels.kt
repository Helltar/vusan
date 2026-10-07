package com.helltar.vusan.tools.klipy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What KLIPY keeps in separate libraries, each behind its own path and with its own file layout. */
enum class KlipyKind(val value: String, val path: String) {
    GIF("gif", "gifs"),
    MEME("meme", "static-memes"),
    CLIP("clip", "clips"),
}

@Serializable
data class KlipySearchResponse(
    val result: Boolean = false,
    val data: KlipyPage = KlipyPage(),
)

@Serializable
data class KlipyPage(val data: List<KlipyItem> = emptyList())

@Serializable
data class KlipyItem(
    val slug: String = "",
    val title: String = "",
    val file: JsonObject? = null,
)

/**
 * The file to send for an item of [kind], exactly as the API gave it, or `null` when it lists none.
 *
 * A GIF and a meme come in sizes, each holding formats; a clip is one file per format. A GIF goes out
 * as its MP4, which is what a GIF in a chat is anyway.
 */
internal fun KlipyItem.mediaUrl(kind: KlipyKind): String? =
    when (kind) {
        KlipyKind.GIF -> sized("mp4") ?: sized("gif")
        KlipyKind.MEME -> sized("png") ?: sized("jpg") ?: sized("webp")
        KlipyKind.CLIP -> file?.get("mp4").text()
    }

private fun KlipyItem.sized(format: String): String? =
    SIZES.firstNotNullOfOrNull { size ->
        ((file?.get(size) as? JsonObject)?.get(format) as? JsonObject)?.get("url").text()
    }

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private val SIZES = listOf("hd", "md")
