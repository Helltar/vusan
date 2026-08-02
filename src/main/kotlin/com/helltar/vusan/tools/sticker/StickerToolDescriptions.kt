package com.helltar.vusan.tools.sticker

internal object StickerToolDescriptions {

    const val SEND_STICKER =
        "Sends one of the stickers this chat already uses, listed with their ids in `<sticker_catalog>` in your context. " +
                "A sticker is a whole reply on its own, the way a person answers a joke with a sticker instead of typing — send it instead of a text message, never alongside one, and never explain or describe it afterwards. " +
                "Use it rarely, and only where a wordless reaction is genuinely the better answer: a joke landing, mock outrage, agreement, sympathy, a greeting between people who know each other. " +
                "Never use it for something the user actually asked you to answer, for factual replies, or when the conversation is serious. " +
                "Pick by the meaning written in the catalog line, not by the emoji alone, and if nothing there fits the moment, send nothing — a mismatched sticker reads worse than plain words. " +
                "`setReaction` marks the user's own message with an emoji; this sends a message from you. " +
                "Do not do both in one turn. " +
                "The catalog is missing entirely in a chat where nobody has used stickers yet, and then there is nothing to send."

    const val STICKER_ID =
        "Required. " +
                "Numeric id of a sticker from `<sticker_catalog>`, without the leading `#`. " +
                "Only ids listed there exist — never guess one."
}
