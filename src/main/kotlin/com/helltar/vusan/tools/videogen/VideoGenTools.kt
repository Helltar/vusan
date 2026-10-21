package com.helltar.vusan.tools.videogen

import com.helltar.vusan.common.dataUrl
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.common.sniffedImageMimeType
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.tools.imagegen.SelfImage
import com.helltar.vusan.tools.keepOnShelf
import com.helltar.vusan.tools.keptNotSent
import com.helltar.vusan.tools.refusedByChat
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard
import io.github.oshai.kotlinlogging.KotlinLogging

class VideoGenTools(
    private val client: XaiVideoClient,
    private val outbox: BotOutbox,
    private val attachedFiles: List<AttachedFile> = emptyList(),
    private val selfImage: SelfImage? = null,
) : ToolSet {

    @Tool(VideoGenToolDescriptions.GENERATE_VIDEO)
    suspend fun generateVideo(
        @Arg(VideoGenToolDescriptions.PROMPT)
        prompt: String,
        @Arg(VideoGenToolDescriptions.START_FRAME)
        startFrame: AttachedFile? = null,
        @Arg(VideoGenToolDescriptions.END_FRAME)
        endFrame: AttachedFile? = null,
        @Arg(VideoGenToolDescriptions.REFERENCES)
        references: List<AttachedFile> = emptyList(),
        @Arg(VideoGenToolDescriptions.WITH_YOURSELF)
        withYourself: Boolean = false,
        @Arg(VideoGenToolDescriptions.ASPECT_RATIO)
        aspectRatio: String = DEFAULT_ASPECT_RATIO,
        @Arg(VideoGenToolDescriptions.SECONDS)
        seconds: Int = DEFAULT_SECONDS,
        @Arg(VideoGenToolDescriptions.SOUND)
        sound: Boolean = true,
        @Arg(VideoGenToolDescriptions.SEND)
        send: Boolean = true,
    ): String = suspendToolGuard {
        val text = prompt.requireToolText("prompt", PROMPT_MAX_CHARS)

        require(seconds in 1..MAX_SECONDS) { "seconds must be from 1 to $MAX_SECONDS, got $seconds" }
        require(aspectRatio in ASPECT_RATIOS) { "aspectRatio must be one of ${ASPECT_RATIOS.joinToString()}, got `$aspectRatio`" }

        if (send && !outbox.capabilities.videos) return@suspendToolGuard refusedByChat("videos")

        val self = selfImage.takeIf { withYourself }

        require(!withYourself || self != null) { "there is no photo or description of you to put in a video" }

        val selfPhoto = self?.reference?.let { dataUrl(it.contentType, it.bytes) }
        val pictures = references.map { it.imageDataUrl() } + listOfNotNull(selfPhoto)
        val offset = if (startFrame != null) 1 else 0

        // the bot is its photo where it has one, after the references; without one, the notes are all of it
        val selfName = self?.let { if (selfPhoto != null) "<IMAGE_${references.size + offset}>" else "the main character" }

        val tagged =
            requireNotNull(xaiVideoPrompt(text, references.size, offset, selfName)) {
                "the prompt names a picture it was not given: `<IMAGE_0>` and up name the references in order, " +
                        "and `<SELF>` needs `withYourself`"
            }

        val described = self?.appearance?.let { "$tagged\n\nAbout $selfName: $it" } ?: tagged

        val video =
            render("generate") {
                client.generate(
                    prompt = described,
                    seconds = seconds,
                    aspectRatio = aspectRatio,
                    sound = sound,
                    startFrame = startFrame?.imageDataUrl(),
                    endFrame = endFrame?.imageDataUrl(),
                    references = pictures,
                    speaker = selfName,
                )
            } ?: return@suspendToolGuard MODERATION_ADVICE

        deliver(video, "The video (${video.durationSeconds ?: seconds} s)", send)
    }

    @Tool(VideoGenToolDescriptions.EDIT_VIDEO)
    suspend fun editVideo(
        @Arg(VideoGenToolDescriptions.EDIT_PROMPT)
        prompt: String,
        @Arg(VideoGenToolDescriptions.EDIT_SOURCE)
        video: AttachedFile? = null,
        @Arg(VideoGenToolDescriptions.EDIT_SEND)
        send: Boolean = true,
    ): String = suspendToolGuard {
        val text = prompt.requireToolText("prompt", PROMPT_MAX_CHARS)

        if (send && !outbox.capabilities.videos) return@suspendToolGuard refusedByChat("videos")

        val source = sourceVideo(video)
        val edited = render("edit") { client.edit(text, source.mp4DataUrl()) } ?: return@suspendToolGuard MODERATION_ADVICE

        deliver(edited, "The edited video", send)
    }

    @Tool(VideoGenToolDescriptions.EXTEND_VIDEO)
    suspend fun extendVideo(
        @Arg(VideoGenToolDescriptions.EXTEND_PROMPT)
        prompt: String,
        @Arg(VideoGenToolDescriptions.EXTEND_SOURCE)
        video: AttachedFile? = null,
        @Arg(VideoGenToolDescriptions.EXTEND_SECONDS)
        seconds: Int = DEFAULT_SECONDS,
        @Arg(VideoGenToolDescriptions.EXTEND_SEND)
        send: Boolean = true,
    ): String = suspendToolGuard {
        val text = prompt.requireToolText("prompt", PROMPT_MAX_CHARS)

        require(seconds in MIN_EXTENSION_SECONDS..MAX_EXTENSION_SECONDS) {
            "seconds must be from $MIN_EXTENSION_SECONDS to $MAX_EXTENSION_SECONDS, got $seconds"
        }

        if (send && !outbox.capabilities.videos) return@suspendToolGuard refusedByChat("videos")

        val source = sourceVideo(video)
        val extended = render("extend") { client.extend(text, source.mp4DataUrl(), seconds) } ?: return@suspendToolGuard MODERATION_ADVICE
        val length = source.durationSeconds?.plus(seconds)

        deliver(RenderedVideo(extended.bytes, length), "The extended video", send)
    }

    // the video, or null when xAI's moderation withheld it, which the model is told rather than failed with
    private suspend fun render(action: String, block: suspend () -> RenderedVideo): RenderedVideo? =
        runCatching { block() }
            .getOrElse { e ->
                e.rethrowIfCancellation()

                if (e is VideoModerationBlocked) {
                    log.info { "Video $action withheld by moderation" }
                    return null
                }

                throw e
            }

    private suspend fun deliver(video: RenderedVideo, what: String, send: Boolean): String {
        val label = keepOnShelf(VIDEO_FILENAME, video.bytes)

        if (!send) return keptNotSent("$what (${video.bytes.size} bytes)", label)

        outbox.enqueue(BotOutput.Video(bytes = video.bytes, filename = VIDEO_FILENAME, durationSeconds = video.durationSeconds))

        return "$what is queued (${video.bytes.size} bytes)${label?.let { " and kept as `$it`" }.orEmpty()}. " +
                "Do not add a separate user-facing confirmation."
    }

    private fun sourceVideo(named: AttachedFile?): AttachedFile {
        val candidate =
            requireNotNull(named ?: attachedFiles.firstOrNull { it.kind == AttachedFileKind.VIDEO }) {
                "no video is attached in this turn; name one by label, or ask the user to send or reply to one"
            }

        require(candidate.kind == AttachedFileKind.VIDEO) { "`${candidate.name}` is not a video" }

        return candidate
    }

    private companion object {
        const val DEFAULT_ASPECT_RATIO = "16:9"
        const val DEFAULT_SECONDS = 6
        const val MAX_SECONDS = 15
        const val MIN_EXTENSION_SECONDS = 2
        const val MAX_EXTENSION_SECONDS = 10
        const val PROMPT_MAX_CHARS = 4_000
        const val VIDEO_FILENAME = "video.mp4"

        val ASPECT_RATIOS = listOf("16:9", "9:16", "1:1", "4:3", "3:4", "3:2", "2:3", "21:9", "5:2")

        const val MODERATION_ADVICE =
            "xAI's moderation rejected the finished video, and the rendering was billed all the same. " +
                    "Tell the user it was blocked; a clearly different scene may pass, the same one reworded will not, so do not retry it on your own."

        val log = KotlinLogging.logger {}
    }
}

/**
 * The prompt as xAI reads it, or `null` when it names a picture the call was not given.
 *
 * The model names its references from `<IMAGE_0>` and itself as `<SELF>`, while xAI numbers every image
 * the request carries: a start frame is `<IMAGE_0>`, so [offset] moves the references up by one, and
 * [selfName] is what `<SELF>` becomes — `null` when the bot is not in the video.
 */
internal fun xaiVideoPrompt(prompt: String, references: Int, offset: Int, selfName: String?): String? {
    if (IMAGE_TAG.findAll(prompt).any { it.groupValues[1].toInt() >= references }) return null
    if (SELF_TAG in prompt && selfName == null) return null

    val numbered = IMAGE_TAG.replace(prompt) { "<IMAGE_${it.groupValues[1].toInt() + offset}>" }

    return selfName?.let { numbered.replace(SELF_TAG, it) } ?: numbered
}

private val IMAGE_TAG = Regex("""<IMAGE_(\d+)>""")
private const val SELF_TAG = "<SELF>"

// what xAI reads as a picture: JPEG, PNG and WebP, judged by the bytes rather than a name
private val PICTURE_TYPES = setOf("image/jpeg", "image/png", "image/webp")

private suspend fun AttachedFile.imageDataUrl(): String {
    require(kind == AttachedFileKind.IMAGE) { "`$name` is not a picture" }

    val bytes = loadBytes()
    val type = requireNotNull(sniffedImageMimeType(bytes)?.takeIf { it in PICTURE_TYPES }) { "`$name` is not a JPEG, PNG or WebP picture" }

    return dataUrl(type, bytes)
}

// an MP4 opens with its `ftyp` box, four bytes in
private suspend fun AttachedFile.mp4DataUrl(): String {
    val bytes = loadBytes()

    require(bytes.size > MP4_BRAND_END && bytes.decodeToString(MP4_BRAND_START, MP4_BRAND_END) == "ftyp") { "`$name` is not an MP4 video" }

    return dataUrl("video/mp4", bytes)
}

private const val MP4_BRAND_START = 4
private const val MP4_BRAND_END = 8
