package com.helltar.vusan.tools.videogen

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
import com.helltar.vusan.tools.imagegen.SourceImage
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

        require(namesOnlyWhatItWasGiven(text, references.size, withSelf = self != null)) {
            "the prompt names a picture it was not given: `<IMAGE_0>` and up name the references in order, " +
                    "and `<SELF>` needs `withYourself`"
        }

        val start = startFrame?.picture()
        val end = endFrame?.picture()
        val pictures = references.map { it.picture() }
        val uploadBytes = (listOfNotNull(start, end, self?.reference) + pictures).sumOf { it.bytes.size.toLong() }

        require(uploadBytes <= MAX_UPLOAD_BYTES) {
            "the pictures add up to more than $MAX_UPLOAD_BYTES bytes; use fewer or smaller ones"
        }

        val video =
            render("generate") {
                client.generate(
                    prompt = text,
                    seconds = seconds,
                    aspectRatio = aspectRatio,
                    sound = sound,
                    startFrame = start,
                    endFrame = end,
                    references = pictures,
                    self = self,
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
        val bytes = source.loadUpload()
        val edited = render("edit") { client.edit(text, bytes, source.name) } ?: return@suspendToolGuard MODERATION_ADVICE

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
        val bytes = source.loadUpload()

        val extended =
            render("extend") { client.extend(text, bytes, source.name, seconds, source.durationSeconds) }
                ?: return@suspendToolGuard MODERATION_ADVICE

        deliver(extended, "The extended video", send)
    }

    // the video, or null when the provider's moderation withheld it, which the model is told rather than failed with
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
            "The provider's moderation rejected the finished video, and the rendering was billed all the same. " +
                    "Tell the user it was blocked; a clearly different scene may pass, the same one reworded will not, so do not retry it on your own."

        val log = KotlinLogging.logger {}
    }
}

/** Whether [prompt] names only what the call was given: `<IMAGE_0>` and up for its [references], `<SELF>` only [withSelf]. */
internal fun namesOnlyWhatItWasGiven(prompt: String, references: Int, withSelf: Boolean): Boolean =
    IMAGE_TAG.findAll(prompt).all { (it.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE) < references } && (withSelf || SELF_TAG !in prompt)

// how the model names its references and itself in a prompt, whatever the provider numbers them as
internal val IMAGE_TAG = Regex("""<IMAGE_(\d+)>""")
internal const val SELF_TAG = "<SELF>"

// one request carries every picture or the video inline, so what it uploads is bounded the way an image
// edit's is: by what a single upload can carry before its timeout, not by any provider's own limit
private const val MAX_UPLOAD_BYTES = 45L * 1024 * 1024

private suspend fun AttachedFile.picture(): SourceImage {
    require(kind == AttachedFileKind.IMAGE) { "`$name` is not a picture" }

    val bytes = loadUpload()

    return SourceImage(bytes, name, requireNotNull(sniffedImageMimeType(bytes)) { "`$name` is not a picture that can be read" })
}

private suspend fun AttachedFile.loadUpload(): ByteArray {
    fileSizeBytes?.let { require(it <= MAX_UPLOAD_BYTES) { "`$name` is too large to send ($it bytes, limit $MAX_UPLOAD_BYTES)" } }

    return loadBytes().also { require(it.size <= MAX_UPLOAD_BYTES) { "`$name` is too large to send (${it.size} bytes, limit $MAX_UPLOAD_BYTES)" } }
}
