package com.helltar.vusan.tools.vision

internal object VisionToolDescriptions {

    const val DESCRIBE_IMAGE =
        "Describes the image attached to the request (a Telegram photo or image document, on the current message or the one it replies to) using vision. " +
                "Use this when the user asks what is visible in the image, asks to explain it, or asks to read visible text/OCR from it. " +
                "To transform or analyze the image programmatically (resize, filters, colors, dimensions) use `codeExecution` instead — the same file is in its working directory. " +
                "Does nothing when no image is attached, or when the attached file is not an image."

    const val FOCUS =
        "Optional short focus from the user's request, for example: visible text, UI error, object, person description, meme meaning."
}
