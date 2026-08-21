package com.helltar.vusan.tools.tgchannel

internal object TelegramChannelToolDescriptions {

    const val READ_TELEGRAM_CHANNEL_POSTS =
        "Reads posts from a public Telegram channel by username or t.me link. " +
                "Use when the user asks to read, summarize, review, or evaluate a public Telegram channel, " +
                """or asks what a channel has posted over some period ("what is new in this channel today"). """ +
                "Each post comes back with its date, view and reaction counts, media kinds, links, " +
                "and a description of its images when they carry the content. " +
                "Use these posts as your evidence and answer from them; do not paste the listing back to the user. " +
                "The header carries the exact post count, so answer how many and how often from it and never by counting the entries below it. " +
                "Works with public channels available at `https://t.me/s/<username>`; private channels and invite links are not supported."

    const val CHANNEL =
        "Public Telegram channel username or link, for example `@example_channel` or `https://t.me/example_channel`."

    const val WINDOW =
        "How far back to read, as a duration ending now: `6h`, `24h`, `2d`, `7d`. " +
                "Set it whenever the request names a period, and leave it empty to just read the newest posts. " +
                "Reaches back at most `30d`, and a channel that posts faster than the window says so in the header."

    const val QUERY =
        "Search the channel instead of reading it in order, for example `roadmap` or `release`. " +
                "Use when the request is about whether or when a channel wrote about something. " +
                "Combines with `window` to search inside a period. Leave empty to read every post."

    const val MAX_POSTS =
        "How many posts to read at most. " +
                "Leave `0` to decide automatically: the newest 12 without a `window`, and whatever the `window` holds with one. " +
                "Set it only to deliberately cap a busy channel."

    const val DESCRIBE_IMAGES =
        "Whether to run vision on images in the posts. " +
                "Keep `true` for channels whose posts are pictures with little text, such as memes, screenshots, art, or charts. " +
                "Set `false` when only the wording matters and the pictures are decoration, which makes a long window much cheaper."

    const val IMAGE_FOCUS =
        "Optional focus for image vision, for example: project quality, UI screenshots, gameplay, visible text, design, product state."
}
