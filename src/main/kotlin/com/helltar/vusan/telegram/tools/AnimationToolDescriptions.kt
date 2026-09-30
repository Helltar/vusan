package com.helltar.vusan.telegram.tools

internal object AnimationToolDescriptions {

    const val SEND_ANIMATION =
        "Sends a GIF or silent H.264 MP4 already in this chat as a Telegram animation, using the exact `file_id` from message metadata. " +
                "Use when the user asks to send an existing video as an animation rather than an ordinary video or document. " +
                "Downloads the file with `getFile` and reuploads its unchanged bytes through `sendAnimation`, because resending by `file_id` cannot change media type. " +
                "This download-and-reupload path supports files up to $MAX_TELEGRAM_FILE_MB MB, even though `sendAnimation` itself allows uploads up to 50 MB. " +
                "The input must already be a GIF or an H.264 MP4 without sound; this tool does not remove audio or transcode, and an `.mp4` extension alone does not prove compatibility. " +
                "If conversion is needed, use the sandbox first and send the resulting compatible file into the chat and call this tool in a later turn once its new `file_id` is available in message metadata. " +
                "The animation is queued for delivery at the end of the turn; do not claim it has already been sent. " +
                "After success, do not send a separate confirmation unless the user asked for accompanying text."
}
