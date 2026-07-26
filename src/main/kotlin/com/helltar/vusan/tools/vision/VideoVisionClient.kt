package com.helltar.vusan.tools.vision

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.request.AttachedFile

/**
 * Answers about a video the only way a chat model can: from still frames sampled out of it, plus its
 * speech when speech-to-text is configured.
 */
class VideoVisionClient(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val sampler: VideoSampler = FfmpegVideoSampler(),
    private val transcriber: VideoAudioTranscriber? = null
) {

    companion object {
        const val MAX_FRAMES = 8
    }

    suspend fun describe(video: AttachedFile, bytes: ByteArray, focus: String): String {
        val frames = sampler.sampleFrames(bytes, video.durationSeconds, MAX_FRAMES)

        if (frames.isEmpty()) {
            return "No frame could be read out of `${video.name}`, " +
                    "so it is either broken or in a format that cannot be opened."
        }

        val transcript =
            transcriber?.let { stt ->
                sampler.extractAudio(bytes)?.let { stt.transcribeOrNull(it, video.durationSeconds) }
            }

        val description = execute(video, frames, samplingNote(video.durationSeconds, frames.size), transcript, focus)

        // the agent gets the speech verbatim as well, so it can quote what was said instead of
        // paraphrasing it out of the description.
        return transcript?.let { "$description\n\n${xmlBlock("audio_transcript", it)}" } ?: description
    }

    /** Describes the single preview frame Telegram ships with a video, used when the video itself is unreachable. */
    suspend fun describePreviewFrame(video: AttachedFile, frame: ByteArray, focus: String): String =
        execute(
            video = video,
            frames = listOf(frame),
            samplingNote =
                "The image below is only the preview frame of a video that is too large to download, not the video itself. " +
                        "Describe what that one frame shows and say plainly that the rest of the video was not seen.",
            transcript = null,
            focus = focus
        )

    private suspend fun execute(
        video: AttachedFile,
        frames: List<ByteArray>,
        samplingNote: String,
        transcript: String?,
        focus: String
    ): String {
        val description =
            promptExecutor.execute(buildPrompt(video, frames, samplingNote, transcript, focus), model)
                .textContent()
                .trim()

        return description.ifBlank { "Vision returned an empty description for the video." }
    }

    private fun buildPrompt(
        video: AttachedFile,
        frames: List<ByteArray>,
        samplingNote: String,
        transcript: String?,
        focus: String
    ) =
        prompt("vusan-video-vision") {
            system(
                "You describe videos for a chat assistant, working from still frames taken out of one. " +
                        "Be concise, factual, and avoid guessing identities. " +
                        "Mention visible text if any. Reply in the user's language when clear."
            )
            user {
                text(
                    buildString {
                        appendLine("Describe this video for answering the user's request.")
                        appendLine(samplingNote)
                        appendLine("Cover the subject, what happens, scene changes, and any visible text.")
                        appendLine("Do not invent what happens between the frames.")

                        if (transcript != null) {
                            appendLine("What is said in the video is transcribed below; use it as part of the answer.")
                        } else {
                            appendLine("The sound of the video is not available, so do not describe it.")
                        }

                        appendLine("Keep it concise.")

                        if (focus.isNotBlank()) {
                            appendLine()
                            appendLine("User focus:")
                            appendLine(focus.trim())
                        }

                        video.caption?.takeIf { it.isNotBlank() }?.let {
                            appendLine()
                            appendLine(xmlBlock("caption", it))
                        }

                        transcript?.let {
                            appendLine()
                            appendLine(xmlBlock("audio_transcript", it))
                        }
                    }
                )

                frames.forEachIndexed { index, frame ->
                    image(
                        AttachmentSource.Image(
                            content = AttachmentContent.Binary.Bytes(frame),
                            format = "jpeg",
                            mimeType = "image/jpeg",
                            fileName = "frame-${index + 1}.jpg"
                        )
                    )
                }
            }
        }

    private fun samplingNote(durationSeconds: Int?, frameCount: Int): String =
        durationSeconds
            ?.takeIf { it > 0 }
            ?.let { "The $frameCount images below are frames spread evenly across a $it-second video, in chronological order." }
            ?: "The $frameCount images below are frames taken at a fixed interval from the start of the video, in chronological order."
}
