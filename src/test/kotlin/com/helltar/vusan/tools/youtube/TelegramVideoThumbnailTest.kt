package com.helltar.vusan.tools.youtube

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramVideoThumbnailTest {

    @Test
    fun `downscales an oversized thumbnail preserving aspect ratio`() {
        val result = assertNotNull(imageBytes(1280, 720, "jpg").asTelegramVideoThumbnail())

        val decoded = ImageIO.read(result.inputStream())

        assertEquals(320, decoded.width)
        assertEquals(180, decoded.height)
    }

    @Test
    fun `keeps a small thumbnail at its original size`() {
        val result = assertNotNull(imageBytes(100, 50, "jpg").asTelegramVideoThumbnail())

        val decoded = ImageIO.read(result.inputStream())

        assertEquals(100, decoded.width)
        assertEquals(50, decoded.height)
    }

    @Test
    fun `converts png with alpha to jpeg`() {
        val png = imageBytes(400, 400, "png", BufferedImage.TYPE_INT_ARGB)

        val result = assertNotNull(png.asTelegramVideoThumbnail())

        assertTrue(isJpeg(result), "Expected JPEG magic bytes")
    }

    @Test
    fun `returns null for undecodable bytes`() {
        assertNull(byteArrayOf(1, 2, 3).asTelegramVideoThumbnail())
        assertNull(ByteArray(0).asTelegramVideoThumbnail())
    }

    private fun imageBytes(
        width: Int,
        height: Int,
        format: String,
        type: Int = BufferedImage.TYPE_INT_RGB
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(width, height, type), format, output)
        return output.toByteArray()
    }

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
}
