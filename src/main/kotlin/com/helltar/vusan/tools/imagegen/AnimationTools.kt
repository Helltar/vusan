package com.helltar.vusan.tools.imagegen

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.config.ImageProviderConfig
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.tools.imagegen.ImageGenTools.Companion.IMAGE_PROMPT_MAX_CHARS
import com.helltar.vusan.tools.keepOnShelf
import com.helltar.vusan.tools.keptNotSent
import com.helltar.vusan.tools.refusedByChat
import com.helltar.vusan.tools.suspendToolGuard
import io.github.oshai.kotlinlogging.KotlinLogging

/** Animated GIFs drawn by the image model, from a description, from pictures, or of the bot itself. */
class AnimationTools(
    private val client: ImageClient,
    private val config: ImageProviderConfig,
    private val outbox: BotOutbox,
    private val attachedFiles: List<AttachedFile> = emptyList(),
    private val selfImage: SelfImage? = null,
) : ToolSet {

    @Tool(AnimationToolDescriptions.ANIMATE_IMAGE)
    suspend fun animateImage(
        @Arg(AnimationToolDescriptions.PROMPT)
        prompt: String,
        @Arg(AnimationToolDescriptions.FRAMES)
        frames: Int = DEFAULT_FRAMES,
        @Arg(AnimationToolDescriptions.FRAME_DURATION_MS)
        frameDurationMs: Int = DEFAULT_FRAME_DURATION_MS,
        @Arg(AnimationToolDescriptions.ANIMATE_ATTACHED)
        animateAttached: Boolean = false,
        @Arg(AnimationToolDescriptions.IMAGES)
        images: List<AttachedFile> = emptyList(),
        @Arg(AnimationToolDescriptions.WITH_YOURSELF)
        withYourself: Boolean = false,
        @Arg(AnimationToolDescriptions.SEND)
        send: Boolean = true,
    ): String = suspendToolGuard {
        val trimmed = prompt.trim()

        if (trimmed.isEmpty())
            return@suspendToolGuard "Animation prompt is empty — describe what moves and how."

        if (trimmed.length > IMAGE_PROMPT_MAX_CHARS)
            return@suspendToolGuard "Animation prompt is ${trimmed.length} characters, " +
                    "which exceeds the $IMAGE_PROMPT_MAX_CHARS-character limit. Shorten it and try again."

        if (frames !in ImageAnimation.FRAMES)
            return@suspendToolGuard "`frames` must be from ${ImageAnimation.FRAMES.first} to ${ImageAnimation.FRAMES.last}, not $frames."

        if (frameDurationMs !in ImageAnimation.FRAME_DURATION_MS)
            return@suspendToolGuard "`frameDurationMs` must be from ${ImageAnimation.FRAME_DURATION_MS.first} " +
                    "to ${ImageAnimation.FRAME_DURATION_MS.last}, not $frameDurationMs."

        if (send && !outbox.capabilities.stickersAndAnimations)
            return@suspendToolGuard refusedByChat("GIFs")

        val self = selfImage?.reference?.takeIf { withYourself }
        val fromPictures = animateAttached || images.isNotEmpty()

        val collected =
            if (fromPictures)
                when (val found = collectEditSources(images.ifEmpty { attachedFiles }, self, client.maxEditImages)) {
                    is EditSources.Refused -> return@suspendToolGuard found.answer
                    is EditSources.Ready -> found
                }
            else
                null

        val sources = collected?.images ?: listOfNotNull(self)
        val appearance = selfImage?.appearance?.takeIf { withYourself }

        val instruction =
            when {
                self == null -> trimmed.withAppearance(appearance)
                sources.size > 1 -> selfInEditPrompt(trimmed, appearance, sources.size - 1)
                else -> selfPortraitPrompt(trimmed, appearance)
            }

        val animation = ImageAnimation(frames, frameDurationMs)

        val bytes =
            runCatching { client.animate(instruction, sources, animation) }
                .getOrElse { e ->
                    e.rethrowIfCancellation()

                    if (e is ImageModerationBlocked) {
                        log.info { "Animation blocked: stage=[${e.stage}] categories=[${e.categories.joinToString()}]" }
                        return@suspendToolGuard e.advice()
                    }

                    log.warn(e) {
                        "Animation failed: provider=[${config.name}] model=[${config.model}] frames=[$frames] " +
                                "frameDurationMs=[$frameDurationMs] sources=[${sources.size}] promptChars=[${trimmed.length}]"
                    }

                    error("Animation failed: ${e.message ?: e::class.simpleName}")
                }

        val label = keepOnShelf(GIF_FILENAME, bytes)
        val described = "$frames frames of $frameDurationMs ms, ${bytes.size} bytes"
        val left = collected?.leftOut(client.maxEditImages).orEmpty()

        if (!send) return@suspendToolGuard keptNotSent("The GIF ($described)", label) + left

        outbox.enqueue(BotOutput.Animation(bytes = bytes, filename = GIF_FILENAME))

        "GIF queued ($described). Do not add a separate user-facing confirmation.$left"
    }

    private companion object {
        const val DEFAULT_FRAMES = 9
        const val DEFAULT_FRAME_DURATION_MS = 200
        const val GIF_FILENAME = "animation.gif"

        val log = KotlinLogging.logger {}
    }
}
