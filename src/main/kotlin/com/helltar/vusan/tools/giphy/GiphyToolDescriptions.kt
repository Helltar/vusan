package com.helltar.vusan.tools.giphy

internal object GiphyToolDescriptions {

    const val SEARCH_GIFS =
        "Searches for GIFs and returns candidates as `id: title` lines, without sending anything. " +
                "Use when the user asks for a GIF, a meme, or an animated image; an emoji reaction to a message is `setReaction`, not a GIF. " +
                "Read the titles, then pass the id of the one that fits to `sendGif`. " +
                "When none fits, search again with other words, or send nothing and say so."

    const val QUERY =
        "Search term describing the GIF: a mood, action, topic, or phrase."

    const val RATING =
        "Content rating: `g` (general, default), `pg`, `pg-13`, or `r`."

    const val SEND_GIF =
        "Sends one GIF that `searchGifs` returned in this turn. " +
                "Each GIF can be sent once per turn. " +
                "After calling this tool, write a short natural comment for the user; the GIF will be sent automatically."

    const val ID =
        "The candidate's id, exactly as `searchGifs` returned it."
}
