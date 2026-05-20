package com.helltar.vusan.tools.youtube

internal object YouTubeMusicToolDescriptions {

    const val PLAY_FULL_TRACK =
        "Find a full song on YouTube and send it as an audio file to the chat. " +
                "Use when the user asks to play, download, or send a complete track — for example " +
                """"play Imagine Dragons Believer", "play the full song", "send me the audio of ...". """ +
                "After calling this tool, write a short natural comment for the user; the audio will be sent automatically."

    const val PLAY_FULL_TRACK_QUERY =
        "Song title, artist, or both — used as a YouTube search query. " +
                "Examples: `Imagine Dragons Believer`, `Bohemian Rhapsody Queen`, `Daft Punk One More Time`."
}
