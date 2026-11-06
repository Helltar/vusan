package com.helltar.vusan.telegram.tools.chat

internal object ChatPhotoToolDescriptions {

    const val GET_CHAT_PHOTO =
        "Fetches this group's current photo, the avatar on its profile, as Telegram serves it: a JPEG of at most 640×640. " +
                "Use when someone asks to see, describe, redraw or edit the group's photo, or to make something from it. " +
                "To work on it, set `send` to `false` and pass the label the result names to `describeImage`, an image tool or `setChatPhoto`. " +
                "A group without a photo is reported back to you."

    const val SEND =
        "Whether the photo goes to the chat; `true` by default. " +
                "`false` keeps it for a later call instead, which takes it by the label the result names, so the chat does not get a copy of its own avatar."
}
