package com.helltar.vusan.tools.youtube

internal object YouTubeVideoToolDescriptions {

    const val DOWNLOAD_VIDEO =
        "Find a video on YouTube and send it to the chat as a playable video — with picture, not just sound. " +
                "Use when the user wants to watch something: to download, send, or show a YouTube video — for example " +
                """"download this video", "send me that YouTube clip", "find a video of a cat playing piano". """ +
                "If the user only wants the song or audio, use the YouTube music tool instead. " +
                "Quality is automatically capped to fit Telegram's 50 MB upload limit. " +
                "After calling this tool, write a short natural comment for the user; the video will be sent automatically."

    const val DOWNLOAD_VIDEO_QUERY =
        "A YouTube search query (title, topic, or both) or a direct YouTube URL. " +
                "Examples: `cat playing piano`, `SpaceX Starship launch`, " +
                "`https://www.youtube.com/watch?v=dQw4w9WgXcQ`."
}
