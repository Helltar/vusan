package com.helltar.vusan.tools.youtube

internal object YouTubeMusicToolDescriptions {

    const val PLAY_FULL_TRACK =
        "Find a song on YouTube and send it to the chat as an audio file — audio only, no picture. " +
                "Use when the user wants to listen to music: to play, download, or send a track — for example " +
                """"play Imagine Dragons Believer", "play the full song", "send me the audio of ...". """ +
                "If the user wants to watch the clip (anything visual), use the YouTube video tool instead. " +
                "After calling this tool, write a short natural comment for the user; the audio will be sent automatically."

    const val PLAY_FULL_TRACK_QUERY =
        "Song title, artist, or both — used as a YouTube search query. " +
                "Examples: `Imagine Dragons Believer`, `Bohemian Rhapsody Queen`, `Daft Punk One More Time`."
}
