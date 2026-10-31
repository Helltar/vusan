package com.helltar.vusan.tools.tgchannel

import java.time.Instant

/** How many posts one `https://t.me/s/<username>` page carries. */
internal const val POSTS_PER_PAGE = 20

data class TelegramChannelPage(
    val username: String,
    val title: String,
    val url: String,
    val posts: List<TelegramChannelPost>,
    /**
     * The `?before=` id loading the batch before this page, taken from the widget's "load more" link. `null`
     * once telegram offers none, which is how the walk knows it reached the channel's start.
     */
    val olderThanCursor: Long?,
    /**
     * False when t.me redirected away from `/s/`: the username is not a channel with a public preview. Tells
     * "nothing to read" from "cannot be read at all".
     */
    val previewAvailable: Boolean = true,
)

data class TelegramChannelPost(
    val id: String,
    val url: String,
    val postedAt: Instant?,
    val text: String,
    val views: String?,
    /** Total reactions on the post, for ranking which posts are worth vision. */
    val reactionCount: Int,
    /**
     * The strongest few reactions with a readable glyph, e.g. `😁 2380 ❤ 173`. Null when the channel only uses
     * custom emoji, still counted in [reactionCount].
     */
    val reactions: String?,
    val forwardedFrom: String?,
    val replyTo: String?,
    val linkPreview: String?,
    val mediaKinds: List<String>,
    val imageUrls: List<String>,
    val links: List<String>,
)

class TelegramChannelImage(
    val url: String,
    val bytes: ByteArray,
    val mimeType: String,
    val filename: String,
)
