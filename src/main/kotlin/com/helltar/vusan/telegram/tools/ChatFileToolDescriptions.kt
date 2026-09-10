package com.helltar.vusan.telegram.tools

internal object ChatFileToolDescriptions {

    const val SEND_CHAT_FILE =
        "Sends a file that is already in this chat back into it as a Telegram document, named by the `file_id` from the message metadata. " +
                "Use when the user asks to download, save, or get the file behind a sticker, photo, GIF, video, voice message, audio track, or document, for example `download this sticker` in a reply to one. " +
                "The stored bytes are sent unchanged, so a sticker arrives as `.webp` (`.tgs` or `.webm` when it is animated) and a photo as the `.jpg` Telegram kept. " +
                "Telegram serves bots files of at most $MAX_TELEGRAM_FILE_MB MB; a larger one is reported back to you instead of being sent. " +
                "Use `downloadFile` for an `http` or `https` link, and `sendFile` for text you wrote yourself. " +
                "After calling this tool, write a short natural comment for the user; the document will be sent automatically."

    const val CHAT_FILE_ID =
        "Required `file_id`, copied exactly from the `file_id` metadata line of the message that carries the file. " +
                "A `file_unique_id` cannot be downloaded, and a guessed or remembered id never works."

    const val CHAT_FILENAME =
        "Optional file name including extension, for example `sticker.webp` or `photo.jpg`. " +
                "Leave empty to keep the name Telegram reports; a name given without an extension takes Telegram's own."
}
