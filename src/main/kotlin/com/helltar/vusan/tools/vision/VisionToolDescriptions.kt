package com.helltar.vusan.tools.vision

internal object VisionToolDescriptions {

    const val DESCRIBE_IMAGE =
        "Describes an image using vision: the one attached to the request (a Telegram photo or image document, on the current message or the one it replies to), or the one `file` names. " +
                "Use this when the user asks what is visible in the image, asks to explain it, or asks to read visible text/OCR from it. " +
                "To transform or analyze the image programmatically (resize, filters, colors, dimensions) use `runCommand` instead — the same file reaches the turn's directory under `turns/` in the sandbox. " +
                "Does nothing when no image is attached or named, or when the file is not an image."

    const val FOCUS =
        "Optional short focus from the user's request, for example: visible text, UI error, object, person description, meme meaning."

    const val IMAGE_FILE =
        "Optional image to look at instead of the attachment: a file an earlier call made (`#3/1`) or one in the sandbox (`sandbox:frames/012.jpg`)."

    const val DESCRIBE_VIDEO =
        "Watches a video through frames taken out of it: the one attached to the request (a Telegram video, video note, GIF, or video document, on the current message or the one it replies to), or the one `file` names. " +
                "Use this when the user asks what is in the video, what happens in it, what is said in it, or asks to summarize it. " +
                "For a video on YouTube, by link or by name, read its subtitles with the YouTube transcript tool instead. " +
                "The result also carries what is spoken in the video whenever its sound could be transcribed. " +
                "Frames are sampled rather than continuous, so fast motion between them is not visible. " +
                "A video too large for Telegram to serve falls back to its single preview frame, and the result says so. " +
                "Does nothing when no video is attached or named; for an image call `describeImage` instead."

    const val VIDEO_FOCUS =
        "Optional short focus from the user's request, for example: what happens, visible text, who is speaking, product shown, meme meaning."

    const val VIDEO_FILE =
        "Optional video to watch instead of the attachment: a file an earlier call made (`#4/1`) or one in the sandbox (`sandbox:cut.mp4`)."
}
