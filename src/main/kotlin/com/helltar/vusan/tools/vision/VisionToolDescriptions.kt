package com.helltar.vusan.tools.vision

internal object VisionToolDescriptions {

    const val DESCRIBE_REPLIED_PHOTO =
        "Describes the photo from the current Telegram replied message using vision. " +
                "Use this when the current user asks what is visible in the replied photo, asks to explain it, or asks to read visible text/OCR from it. " +
                "Do not use for non-photo replies or when reply metadata/caption is enough."

    const val FOCUS =
        "Optional short focus from the user's request, for example: visible text, UI error, object, person description, meme meaning."
}
