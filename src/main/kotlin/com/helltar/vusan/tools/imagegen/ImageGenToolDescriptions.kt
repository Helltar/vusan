package com.helltar.vusan.tools.imagegen

import com.helltar.vusan.tools.imagegen.ImageGenTools.Companion.IMAGE_PROMPT_MAX_CHARS

internal object ImageGenToolDescriptions {

    const val GENERATE_IMAGE =
        "Generate a brand-new image from a text description and send it to the chat as a photo. " +
                """Use when the user asks to draw, paint, generate, imagine, or make a picture/image of something ("draw a cat in space", "generate a logo", "make me a wallpaper"). """ +
                "This creates original AI art — it does NOT search the web: use `searchImages` when the user wants a real photo of a real thing, and `codeExecution` for charts or plots from data. " +
                "Write a vivid, detailed `prompt`; the model follows detailed prompts far better than terse ones. " +
                "The resulting image is AI-generated. " +
                "After a successful call, do not send a separate confirmation; the image is delivered automatically. " +
                "Use `sendMessage` only if the user explicitly asked for accompanying text or if the tool fails."

    const val PROMPT =
        "Detailed description of the image to generate, written in English for best results (translate the user's request if needed). " +
                "Describe the subject, style, composition, lighting, and mood. " +
                "Up to $IMAGE_PROMPT_MAX_CHARS characters."

    const val ORIENTATION =
        "Aspect ratio of the image. " +
                "Use `square` (default, 1:1), `portrait` (tall, 2:3) for posters, phone wallpapers, or full-body characters, " +
                "or `landscape` (wide, 3:2) for scenery, banners, or desktop wallpapers."
}
