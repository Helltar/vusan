package com.helltar.vusan.tools.tgchannel

internal object TelegramChannelToolDescriptions {

    const val READ_TELEGRAM_CHANNEL_POSTS =
        "Reads recent posts from a public Telegram channel by username or t.me link. " +
                "Use when the user asks to read, summarize, review, or evaluate posts from a public Telegram channel. " +
                "Works with public channels available at `https://t.me/s/<username>`; private channels and invite links are not supported."

    const val CHANNEL =
        "Public Telegram channel username or link, for example `@helltar_com` or `https://t.me/helltar_com`."

    const val MAX_POSTS =
        "How many recent posts to read, from 1 to 20. Prefer 10 to 12 for summaries."

    const val DESCRIBE_IMAGES =
        "Whether to run vision on photos from the posts. " +
                "Use `true` when visual content matters for a review, project evaluation, UI analysis, OCR, or image-based summary."

    const val IMAGE_FOCUS =
        "Optional focus for image vision, for example: project quality, UI screenshots, gameplay, visible text, design, product state."
}
