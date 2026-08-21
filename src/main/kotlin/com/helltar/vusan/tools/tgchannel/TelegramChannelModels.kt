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
     * Id to pass as `?before=` to load the batch preceding this page, taken from the widget's own
     * "load more" link. `null` once Telegram stops offering one, which is how the walk learns it
     * reached the start of the channel.
     */
    val olderThanCursor: Long?,
    /**
     * False when t.me redirected away from `/s/`, meaning the username is not a channel with a
     * public web preview. Distinguishes "nothing to read" from "cannot be read at all".
     */
    val previewAvailable: Boolean = true
)

data class TelegramChannelPost(
    val id: String,
    val url: String,
    val postedAt: Instant?,
    val text: String,
    val views: String?,
    /** Sum over every reaction on the post, used to rank which posts are worth vision. */
    val reactionCount: Int,
    /**
     * The strongest few reactions that have a readable glyph, e.g. `😁 2380 ❤ 173`. Null when the
     * channel only uses custom emoji, whose count still lands in [reactionCount].
     */
    val reactions: String?,
    val forwardedFrom: String?,
    val replyTo: String?,
    val linkPreview: String?,
    val mediaKinds: List<String>,
    val imageUrls: List<String>,
    val links: List<String>
) {
    val hasMedia: Boolean get() = mediaKinds.isNotEmpty()
}

class TelegramChannelImage(
    val url: String,
    val bytes: ByteArray,
    val mimeType: String,
    val filename: String
)
