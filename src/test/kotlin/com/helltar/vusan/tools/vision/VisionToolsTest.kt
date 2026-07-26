package com.helltar.vusan.tools.vision

import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisionToolsTest {

    @Test
    fun `describeImage returns no-image message when no image is attached`() = runBlocking {
        val executor = FakePromptExecutor()
        val tools = visionTools(executor, attachedFile = null)

        val result = tools.describeImage("text")

        assertEquals("No image is attached in this turn.", result)
        assertEquals(0, executor.callCount)
    }

    @Test
    fun `describeImage refuses a non-image attachment without calling vision`() = runBlocking {
        var loaded = false
        val file = attachedFile(kind = AttachedFileKind.OTHER, name = "data.csv") {
            loaded = true
            byteArrayOf(1)
        }
        val executor = FakePromptExecutor()
        val tools = visionTools(executor, file)

        val result = tools.describeImage("")

        assertEquals("The attached file `data.csv` is not an image, so it can't be described visually.", result)
        assertEquals(false, loaded)
        assertEquals(0, executor.callCount)
    }

    @Test
    fun `describeImage returns oversize message before loading bytes when metadata is too large`() = runBlocking {
        var loaded = false
        val file = attachedFile(fileSizeBytes = (9 * 1024 * 1024).toLong()) {
            loaded = true
            byteArrayOf(1)
        }
        val executor = FakePromptExecutor()
        val tools = visionTools(executor, file)

        val result = tools.describeImage("objects")

        assertEquals("The image is too large for vision (9437184 bytes, limit 8388608).", result)
        assertEquals(false, loaded)
        assertEquals(0, executor.callCount)
    }

    @Test
    fun `describeImage runs the vision prompt with the focus and returns its text`() = runBlocking {
        val file = attachedFile { byteArrayOf(1, 2, 3) }
        val executor = FakePromptExecutor(response = "A cat on a chair.")
        val tools = visionTools(executor, file)

        val result = tools.describeImage("visible text")

        assertEquals("A cat on a chair.", result)
        assertEquals(1, executor.callCount)
        assertTrue("visible text" in executor.promptText, "expected the user focus to be forwarded into the vision prompt")
    }

    @Test
    fun `describeVideo returns no-video message when nothing is attached`() = runBlocking {
        val executor = FakePromptExecutor()
        val tools = visionTools(executor, attachedFile = null)

        val result = tools.describeVideo("what happens")

        assertEquals("No video is attached in this turn.", result)
        assertEquals(0, executor.callCount)
    }

    @Test
    fun `describeVideo points an image attachment at describeImage`() = runBlocking {
        val executor = FakePromptExecutor()
        val tools = visionTools(executor, attachedFile { byteArrayOf(1) })

        val result = tools.describeVideo("")

        assertContains(result, "is an image, not a video")
        assertContains(result, "`describeImage`")
        assertEquals(0, executor.callCount)
    }

    @Test
    fun `describeVideo describes sampled frames and appends the transcript`() = runBlocking {
        val executor = FakePromptExecutor(response = "A dog jumps into a pool.")
        val sampler = FakeVideoSampler(frames = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)))

        val tools =
            visionTools(
                executor = executor,
                attachedFile = videoAttachment(),
                sampler = sampler,
                transcriber = { _, _ -> "look at him go" }
            )

        val result = tools.describeVideo("what happens")

        assertContains(result, "A dog jumps into a pool.")
        assertContains(result, "<audio_transcript>\nlook at him go\n</audio_transcript>")
        assertEquals(1, executor.callCount)
        assertEquals(3, executor.attachmentCount)
        assertContains(executor.promptText, "12-second video")
        assertContains(executor.promptText, "what happens")
        assertEquals(12, sampler.receivedDurationSeconds)
    }

    @Test
    fun `describeVideo says the sound is unavailable when transcription is off`() = runBlocking {
        val executor = FakePromptExecutor()
        val tools = visionTools(executor, videoAttachment(), transcriber = null)

        val result = tools.describeVideo("")

        assertContains(executor.promptText, "sound of the video is not available")
        assertTrue("<audio_transcript>" !in result, "expected no transcript block without transcription")
    }

    @Test
    fun `describeVideo keeps going when the audio cannot be transcribed`() = runBlocking {
        val executor = FakePromptExecutor()
        val tools = visionTools(executor, videoAttachment(), transcriber = { _, _ -> null })

        val result = tools.describeVideo("")

        assertEquals("description", result)
        assertContains(executor.promptText, "sound of the video is not available")
    }

    @Test
    fun `describeVideo samples at a fixed interval when the duration is unknown`() = runBlocking {
        val executor = FakePromptExecutor()
        val sampler = FakeVideoSampler(frames = listOf(byteArrayOf(1), byteArrayOf(2)))
        val tools = visionTools(executor, videoAttachment(durationSeconds = null), sampler)

        tools.describeVideo("")

        assertEquals(null, sampler.receivedDurationSeconds)
        assertContains(executor.promptText, "frames taken at a fixed interval")
    }

    @Test
    fun `describeVideo reports a video ffmpeg could not read`() = runBlocking {
        val executor = FakePromptExecutor()
        val tools = visionTools(executor, videoAttachment(), sampler = FakeVideoSampler(frames = emptyList()))

        val result = tools.describeVideo("")

        assertContains(result, "No frame could be read out of `clip.mp4`")
        assertEquals(0, executor.callCount)
    }

    @Test
    fun `describeVideo falls back to the preview frame of an oversize video`() = runBlocking {
        var videoLoaded = false
        val executor = FakePromptExecutor(response = "A blurry street at night.")
        val sampler = FakeVideoSampler(frames = listOf(byteArrayOf(1)))

        val video =
            videoAttachment(
                fileSizeBytes = (25 * 1024 * 1024).toLong(),
                loadThumbnailBytes = { byteArrayOf(9) },
                loadBytes = {
                    videoLoaded = true
                    byteArrayOf(1)
                }
            )

        val result = visionTools(executor, video, sampler).describeVideo("")

        assertEquals("A blurry street at night.", result)
        assertEquals(false, videoLoaded, "an oversize video must not be downloaded")
        assertEquals(1, executor.attachmentCount)
        assertContains(executor.promptText, "only the preview frame")
        assertEquals(null, sampler.receivedDurationSeconds, "the preview frame needs no frame sampling")
    }

    @Test
    fun `describeVideo explains an oversize video with no preview frame`() = runBlocking {
        val executor = FakePromptExecutor()
        val video = videoAttachment(fileSizeBytes = (25 * 1024 * 1024).toLong())

        val result = visionTools(executor, video).describeVideo("")

        assertContains(result, "could not be downloaded")
        assertEquals(0, executor.callCount)
    }

    @Test
    fun `describeVideo falls back to the preview frame when the download fails`() = runBlocking {
        val executor = FakePromptExecutor()

        val video =
            videoAttachment(
                loadThumbnailBytes = { byteArrayOf(9) },
                loadBytes = { error("Bad Request: file is too big") }
            )

        val result = visionTools(executor, video, FakeVideoSampler(frames = listOf(byteArrayOf(1)))).describeVideo("")

        assertEquals("description", result)
        assertContains(executor.promptText, "only the preview frame")
    }

    private fun visionTools(
        executor: FakePromptExecutor,
        attachedFile: AttachedFile?,
        sampler: VideoSampler = FakeVideoSampler(),
        transcriber: VideoAudioTranscriber? = null
    ): VisionTools =
        VisionTools(
            client = ImageVisionClient(executor, TEST_MODEL),
            videoClient = VideoVisionClient(executor, TEST_MODEL, sampler, transcriber),
            attachedFile = attachedFile
        )

    private fun attachedFile(
        fileSizeBytes: Long? = null,
        kind: AttachedFileKind = AttachedFileKind.IMAGE,
        name: String = "photo.jpg",
        loadBytes: suspend () -> ByteArray
    ): AttachedFile =
        AttachedFile(
            name = name,
            fileSizeBytes = fileSizeBytes,
            mimeType = "image/jpeg",
            kind = kind,
            caption = "caption",
            loadBytes = loadBytes
        )

    private fun videoAttachment(
        fileSizeBytes: Long? = 1_024L,
        durationSeconds: Int? = 12,
        loadThumbnailBytes: (suspend () -> ByteArray)? = null,
        loadBytes: suspend () -> ByteArray = { byteArrayOf(1, 2, 3) }
    ): AttachedFile =
        AttachedFile(
            name = "clip.mp4",
            fileSizeBytes = fileSizeBytes,
            mimeType = "video/mp4",
            kind = AttachedFileKind.VIDEO,
            caption = "caption",
            durationSeconds = durationSeconds,
            loadThumbnailBytes = loadThumbnailBytes,
            loadBytes = loadBytes
        )

    private class FakeVideoSampler(
        private val frames: List<ByteArray> = listOf(byteArrayOf(1)),
        private val audio: ByteArray? = byteArrayOf(5)
    ) : VideoSampler {

        var receivedDurationSeconds: Int? = null
            private set

        override suspend fun sampleFrames(video: ByteArray, durationSeconds: Int?, maxFrames: Int): List<ByteArray> {
            receivedDurationSeconds = durationSeconds
            return frames
        }

        override suspend fun extractAudio(video: ByteArray): ByteArray? = audio
    }
}
