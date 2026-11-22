package com.helltar.vusan.tools.images

/**
 * A page's images include its license badges, icons and small thumbnails of the picture itself, so a picture
 * taken from a page is sent only when its shorter side reaches this.
 */
const val MIN_PAGE_PHOTO_SIDE = 200

// what a page shows besides its pictures; a `.svg` is a drawing, and a wikimedia thumbnail of one ends in
// `.svg.png`
private val decorationNames = listOf("logo", "icon", "favicon", "sprite", "avatar", "badge")

/**
 * The image URLs found on pages that are worth downloading, in the order given: web addresses only, without
 * drawings and decorations. The same picture is often linked twice with a different query string, so the
 * address without one decides duplicates.
 */
internal fun pagePhotoCandidates(urls: List<String>): List<String> =
    urls
        .map { it.trim() }
        .filter { it.startsWith("https://") || it.startsWith("http://") }
        .filterNot { it.isPageDecoration() }
        .distinctBy { it.substringBefore('?') }

private fun String.isPageDecoration(): Boolean {
    val path = substringBefore('?').lowercase()
    val name = path.substringAfterLast('/')

    return ".svg" in path || decorationNames.any { it in name }
}
