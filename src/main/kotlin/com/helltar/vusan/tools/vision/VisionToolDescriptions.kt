package com.helltar.vusan.tools.vision

internal object VisionToolDescriptions {

    const val DESCRIBE_IMAGE =
        "Describes the image attached to the request (a Telegram photo or image document, on the current message or the one it replies to) using vision. " +
                "Use this when the user asks what is visible in the image, asks to explain it, or asks to read visible text/OCR from it. " +
                "To transform or analyze the image programmatically (resize, filters, colors, dimensions) use `runCommand` instead — the same file is placed in the workspace's `inbox/`. " +
                "Does nothing when no image is attached, or when the attached file is not an image."

    const val FOCUS =
        "Optional short focus from the user's request, for example: visible text, UI error, object, person description, meme meaning."

    const val DESCRIBE_VIDEO =
        "Watches the video attached to the request (a Telegram video, video note, GIF, or video document, on the current message or the one it replies to) through frames taken out of it. " +
                "Use this when the user asks what is in the video, what happens in it, what is said in it, or asks to summarize it. " +
                "It only ever sees a video attached to the chat message; for a video on YouTube, by link or by name, read its subtitles with the YouTube transcript tool instead. " +
                "The result also carries what is spoken in the video whenever its sound could be transcribed. " +
                "Frames are sampled rather than continuous, so fast motion between them is not visible. " +
                "A video too large for Telegram to serve falls back to its single preview frame, and the result says so. " +
                "Does nothing when no video is attached; for an image call `describeImage` instead."

    const val VIDEO_FOCUS =
        "Optional short focus from the user's request, for example: what happens, visible text, who is speaking, product shown, meme meaning."
}
