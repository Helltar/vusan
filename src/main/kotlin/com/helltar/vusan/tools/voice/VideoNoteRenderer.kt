package com.helltar.vusan.tools.voice

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.tools.runFfmpeg
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// telegram plays a video note as the circle inscribed in the square and crops the rest, so the diameter is
// the frame size. 384 is what its clients record at.
internal const val VIDEO_NOTE_SIZE = 384

/** Builds the square mp4 telegram plays as a round video message from a portrait and spoken audio. */
fun interface VideoNoteRenderer {

    /** The mp4 bytes, or null when ffmpeg is missing or the render failed. */
    suspend fun render(portrait: ByteArray, speech: ByteArray): ByteArray?
}

class FfmpegVideoNoteRenderer(
    private val ffmpegPath: String = "ffmpeg",
    private val timeout: Duration = 120.seconds,
) : VideoNoteRenderer {

    override suspend fun render(portrait: ByteArray, speech: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        if (portrait.isEmpty() || speech.isEmpty()) return@withContext null

        val workDir = Files.createTempDirectory("video-note-")

        try {
            val portraitFile = workDir.resolve("portrait")
            val speechFile = workDir.resolve("speech")
            val output = workDir.resolve("video-note.mp4")

            Files.write(portraitFile, portrait)
            Files.write(speechFile, speech)

            val command =
                listOf(
                    "-hide_banner", "-nostdin", "-y",
                    "-loop", "1", "-i", portraitFile.toString(),
                    "-i", speechFile.toString(),
                    "-filter_complex", filterGraph(),
                    "-map", "[video]", "-map", "[speech]",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "26",
                    "-pix_fmt", "yuv420p", "-r", FRAME_RATE.toString(),
                    "-c:a", "aac", "-b:a", "96k",
                    "-shortest", "-t", MAX_SECONDS,
                    "-movflags", "+faststart",
                    output.toString(),
                )

            if (!runFfmpeg(command, ffmpegPath, timeout))
                return@withContext null

            output.takeIf { Files.exists(it) }?.let { Files.readAllBytes(it) }?.takeIf { it.isNotEmpty() }
        } catch (e: Throwable) {
            e.rethrowIfCancellation()
            log.warn(e) { "ffmpeg video note render failed" }
            null
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }

    // the bars are drawn twice, a dark copy a pixel below the white one: the portrait may be light or dark
    // anywhere, and a shadow follows the bars where a flat scrim would cut the picture in half.
    private fun filterGraph(): String {
        val bars =
            "showwaves=s=${BAR_COUNT}x$SOURCE_WAVE_HEIGHT:mode=cline:rate=$FRAME_RATE:scale=sqrt:colors=white," +
                    "scale=${WAVE_WIDTH}x$WAVE_HEIGHT:flags=neighbor,format=rgba," +
                    "drawbox=y=$RAIL_Y:h=$RAIL_HEIGHT:color=white:t=fill," +
                    """geq=r=255:g=255:b=255:a='if(lt(mod(X\,$BAR_PITCH)\,$BAR_WIDTH)""" +
                    """*gt(alpha(X\,Y)\,$ALPHA_FLOOR)\,255\,0)'"""

        return "[0:v]scale=$SOURCE_SIZE:$SOURCE_SIZE:force_original_aspect_ratio=increase," +
                "crop=$SOURCE_SIZE:$SOURCE_SIZE,setsar=1," +
                "zoompan=z='$ZOOM':d=1:x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)'" +
                ":s=${VIDEO_NOTE_SIZE}x$VIDEO_NOTE_SIZE:fps=$FRAME_RATE,format=yuv420p[portrait];" +
                "[1:a]asplit=3[speech][shadowAudio][waveAudio];" +
                "[shadowAudio]$bars,colorchannelmixer=rr=0:gg=0:bb=0:aa=0.5[shadow];" +
                "[waveAudio]$bars[wave];" +
                "[portrait][shadow]overlay=${WAVE_X + 1}:${WAVE_Y + 2}[shadowed];" +
                "[shadowed][wave]overlay=$WAVE_X:$WAVE_Y[video]"
    }

    private companion object {
        // the portrait is cropped square at this size and only scaled down to the frame later, so the moving
        // crop has pixels to move into instead of upscaling.
        const val SOURCE_SIZE = 640
        const val FRAME_RATE = 25

        // the waveform must stay inside the circle, which this far down is much narrower than the square, so
        // the band touches neither the sides nor the bottom.
        const val WAVE_WIDTH = 264
        const val WAVE_HEIGHT = 88
        const val WAVE_X = (VIDEO_NOTE_SIZE - WAVE_WIDTH) / 2
        const val WAVE_Y = 240

        // telegram's voice-message shape: separate bars mirrored around a thin rail. showwaves draws one
        // column per bar into a tiny frame, nearest-neighbor upscaling makes each a block, and the comb in
        // `geq` cuts the gaps back out.
        const val BAR_COUNT = 24
        const val BAR_PITCH = WAVE_WIDTH / BAR_COUNT
        const val BAR_WIDTH = 8
        const val SOURCE_WAVE_HEIGHT = 44
        const val RAIL_HEIGHT = 3
        const val RAIL_Y = (WAVE_HEIGHT - RAIL_HEIGHT) / 2

        // upscaling a drawn column also upscales its anti-aliased ends, which made the old line look
        // scratched; anything fainter is dropped, so a bar either reaches a row or does not.
        const val ALPHA_FLOOR = 80

        // a still picture reads as a video that failed to play; breathing in and out is the cheapest motion
        // that need not know the speech's length.
        const val ZOOM = "1.03+0.03*sin(on/40)"

        // telegram rejects a video note over a minute, and a theatrical read can outrun the character limit
        // the text was measured against.
        const val MAX_SECONDS = "59"

        val log = KotlinLogging.logger {}
    }
}
