package com.helltar.vusan.tools.imagegen

import com.helltar.vusan.tools.imagegen.ImageGenTools.Companion.IMAGE_PROMPT_MAX_CHARS

internal object AnimationToolDescriptions {

    const val ANIMATE_IMAGE =
        "Draw a new short looping animated GIF and send it to the chat. " +
                """Use when the user asks you to make, draw, or animate a GIF ("make a gif of a dancing cat", "animate this photo", "a gif of you waving"); to find a ready-made GIF use `searchGifs` instead. """ +
                "It draws from the description alone, or brings to life the images attached to this turn or named in `images` when `animateAttached` is `true`. " +
                "It suits simple motion — a reaction, a wave, a blink, a mascot bouncing, a product turning — and not scenes with dialogue or several shots, which `generateVideo` makes better where it is offered. " +
                "After a successful call, do not send a separate confirmation; the GIF is delivered automatically."

    const val PROMPT =
        "Description of the animation, written in English for best results (translate the user's request if needed). " +
                "Describe the subject and style, then the motion from the first frame to the last, and say whether the last frame should lead seamlessly back to the first. " +
                "Keep one fixed camera and one subject doing one clear movement; ask for no captions or text unless the user wants them. " +
                "Up to $IMAGE_PROMPT_MAX_CHARS characters."

    const val FRAMES =
        "How many frames the GIF has, from `2` to `16`; `9` by default. " +
                "The loop lasts `frames` times `frameDurationMs` (`9` frames of `200` ms is 1.8 seconds), so more frames make it longer, or smoother at a shorter duration."

    const val FRAME_DURATION_MS =
        "How long each frame is shown, in milliseconds, from `20` to `2000`; `200` by default. " +
                "Use `80` to `120` for quick lively motion and `300` or more for a slow one."

    const val ANIMATE_ATTACHED =
        "Whether to animate the images attached to this turn (a photo the user sent, an album, or a replied-to image) instead of drawing from the description alone; `false` by default. " +
                """Set it to `true` for "animate this" or "make my drawing move"; the first image sets the look, so keep it intact unless the user asks otherwise."""

    const val IMAGES =
        "Optional images to animate instead of the attachments, in order: files earlier calls made (`#3/1`) or files in the sandbox (`sandbox:out/mascot.png`). " +
                "Naming any implies `animateAttached`."

    const val WITH_YOURSELF =
        "Whether you are in the animation. " +
                """Set it to `true` for a GIF of you ("a gif of you waving", "you dancing with my cat"), so your own reference photo keeps your face the same as in every other picture you send. """ +
                "Then write the `prompt` about what you do and where, not about your looks."

    const val SEND =
        "Whether the GIF goes to the chat; `true` by default. " +
                "`false` keeps it for a later call instead, another tool or the sandbox, which takes it by the label the result names."
}
