package com.helltar.vusan.telegram.tools.chat

internal object ChatSettingsToolDescriptions {

    const val SET_CHAT_PHOTO =
        "Makes a picture this group's photo. " +
                "Use only when someone asks to change the group's photo or avatar, never on your own initiative. " +
                "It works only if the person asking may change the group's info themselves, and the result says so when they may not. " +
                "Telegram announces the change in the chat itself, so a short word from you is enough."

    const val PHOTO_IMAGE =
        "Optional picture to use instead of the attachment: one an earlier call made (`#3/1`), such as a generated image, or one in the sandbox (`sandbox:out/avatar.png`)."

    const val SET_CHAT_TITLE =
        "Renames this group. " +
                "Use only when someone asks to rename the group, never on your own initiative. " +
                "It works only if the person asking may change the group's info themselves, and the result says so when they may not."

    const val TITLE =
        "Required new title, 1-$MAX_TITLE_CHARS characters, exactly as it should appear."

    const val SET_CHAT_DESCRIPTION =
        "Replaces this group's description, the text shown on its profile. " +
                "Use only when someone asks to change or clear the description, never on your own initiative. " +
                "It works only if the person asking may change the group's info themselves, and the result says so when they may not."

    const val DESCRIPTION =
        "New description, at most $MAX_DESCRIPTION_CHARS characters. " +
                "Leave empty to clear it."
}
