package com.helltar.vusan.tools.vision

import com.helltar.vusan.request.RepliedPhoto
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VisionToolsTest {

    @Test
    fun `describeRepliedPhoto returns no-photo message when no reply photo is available`() = runBlocking {
        val client = FakeRepliedPhotoVisionClient()
        val tools = VisionTools(client, replyPhoto = null)

        val result = tools.describeRepliedPhoto("text")

        assertEquals("No replied photo is available in this turn.", result)
        assertNull(client.receivedPhoto)
    }

    @Test
    fun `describeRepliedPhoto returns oversize message before loading bytes when metadata is too large`() = runBlocking {
        var loaded = false
        val photo = repliedPhoto(fileSizeBytes = (9 * 1024 * 1024).toULong()) {
            loaded = true
            byteArrayOf(1)
        }
        val client = FakeRepliedPhotoVisionClient()
        val tools = VisionTools(client, photo)

        val result = tools.describeRepliedPhoto("objects")

        assertEquals("The replied photo is too large for vision (9437184 bytes, limit 8388608).", result)
        assertEquals(false, loaded)
        assertNull(client.receivedPhoto)
    }

    @Test
    fun `describeRepliedPhoto sends loaded bytes and focus to vision client`() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3)
        val photo = repliedPhoto { bytes }
        val client = FakeRepliedPhotoVisionClient(response = "A cat on a chair.")
        val tools = VisionTools(client, photo)

        val result = tools.describeRepliedPhoto("visible text")

        assertEquals("A cat on a chair.", result)
        assertEquals(photo, client.receivedPhoto)
        assertContentEquals(bytes, client.receivedBytes)
        assertEquals("visible text", client.receivedFocus)
    }

    private fun repliedPhoto(
        fileSizeBytes: ULong? = null,
        loadBytes: suspend () -> ByteArray
    ): RepliedPhoto =
        RepliedPhoto(
            fileId = "file-1",
            fileUniqueId = "unique-1",
            width = 100,
            height = 100,
            fileSizeBytes = fileSizeBytes,
            caption = "caption",
            loadBytes = loadBytes
        )

    private class FakeRepliedPhotoVisionClient(private val response: String = "description") : RepliedPhotoVisionClient {
        var receivedPhoto: RepliedPhoto? = null
        var receivedBytes: ByteArray? = null
        var receivedFocus: String? = null

        override suspend fun describe(photo: RepliedPhoto, bytes: ByteArray, focus: String): String {
            receivedPhoto = photo
            receivedBytes = bytes
            receivedFocus = focus
            return response
        }
    }
}
