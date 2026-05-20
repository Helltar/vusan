package com.helltar.vusan.tools.tgchannel

data class TelegramChannelPage(
    val username: String,
    val title: String,
    val url: String,
    val posts: List<TelegramChannelPost>
)

data class TelegramChannelPost(
    val id: String,
    val url: String,
    val datetime: String?,
    val text: String,
    val views: String?,
    val hasMedia: Boolean,
    val imageUrls: List<String>,
    val links: List<String>
)

class TelegramChannelImage(
    val url: String,
    val bytes: ByteArray,
    val mimeType: String,
    val filename: String
)
