package com.helltar.vusan.tools.klipy

internal object KlipyToolDescriptions {

    const val SEARCH_GIFS =
        "Searches for ready-made GIFs, meme pictures or short video clips and returns candidates as `id: title` lines, without sending anything. " +
                "Use when the user asks for a GIF, a meme, a reaction clip or an animated image; an emoji reaction to a message is `setReaction`, not a GIF. " +
                "Read the titles, then pass the id of the one that fits to `sendGif`. " +
                "When none fits, search again with other words or another `kind`, or send nothing and say so."

    const val QUERY =
        "Search term describing what to find: a mood, action, topic, or phrase."

    const val KIND =
        "What to search: `gif` (a soundless loop, default), `meme` (a still picture with a caption on it), or `clip` (a few seconds of video with sound)."

    const val SEND_GIF =
        "Sends one candidate that `searchGifs` returned in this turn, whatever its kind. " +
                "Each candidate can be sent once per turn. " +
                "After calling this tool, write a short natural comment for the user; the file will be sent automatically."

    const val ID =
        "The candidate's id, exactly as `searchGifs` returned it."
}
