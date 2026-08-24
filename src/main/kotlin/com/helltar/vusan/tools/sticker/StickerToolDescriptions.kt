package com.helltar.vusan.tools.sticker

internal object StickerToolDescriptions {

    const val SEARCH_STICKERS =
        "Searches every described sticker from the sets this chat uses, including ones not shown in `<sticker_catalog>`. " +
                "Use only when a wordless sticker reaction is genuinely better than text but the short catalog has no fitting option. " +
                "Search by a short English phrase naming the subject, action, feeling, or mood, and try at most one concise synonym if nothing matches. " +
                "The returned ids are candidates for `sendSticker`; inspect their descriptions and send nothing if none fits."

    const val SEARCH_QUERY =
        "Required short English search phrase, for example `confused cat`, `mock outrage`, or `warm greeting`."

    const val MAX_RESULTS =
        "Maximum candidates to return, from `1` to `12`. " +
                "Defaults to `8`."

    const val SEND_STICKER =
        "Sends one of the stickers this chat already uses, identified by an id from `<sticker_catalog>` or `searchStickers`. " +
                "A sticker is a whole reply on its own, the way a person answers a joke with a sticker instead of typing — send it instead of a text message, never alongside one, and never explain or describe it afterwards. " +
                "Use it rarely, and only where a wordless reaction is genuinely the better answer: a joke landing, mock outrage, agreement, sympathy, a greeting between people who know each other. " +
                "Never use it for something the user actually asked you to answer, for factual replies, or when the conversation is serious. " +
                "Pick by the meaning written beside the id, not by the emoji alone, and if nothing fits the moment, send nothing — a mismatched sticker reads worse than plain words. " +
                "`setReaction` marks the user's own message with an emoji; this sends a message from you. " +
                "Do not do both in one turn. " +
                "The catalog is missing entirely in a chat where nobody has used stickers yet, and then there is nothing to send."

    const val STICKER_ID =
        "Required. " +
                "Numeric id from `<sticker_catalog>` or `searchStickers`, without the leading `#`. " +
                "Never guess an id."
}
