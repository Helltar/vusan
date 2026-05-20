package com.helltar.vusan.tools.giphy

internal object GiphyToolDescriptions {

    const val SEARCH_GIF =
        "Finds and sends a GIF matching the user's request. " +
                "Use when the user asks for a GIF, meme, reaction, or animated image. " +
                "After calling this tool, write a short natural comment for the user; the GIF will be sent automatically."

    const val QUERY =
        "Search term describing the GIF: a mood, action, topic, or phrase."

    const val RATING =
        "Content rating: `g` (general, default), `pg`, `pg-13`, or `r`."
}
