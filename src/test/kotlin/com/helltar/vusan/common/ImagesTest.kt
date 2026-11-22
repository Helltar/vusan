package com.helltar.vusan.common

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ImagesTest {

    @Test
    fun `a translucent png becomes an opaque jpeg of the same size`() {
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(6, 9, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()

        val jpeg = assertNotNull(reencodedAsJpeg(png))
        val reader = ImageIO.getImageReaders(ImageIO.createImageInputStream(ByteArrayInputStream(jpeg))).next()

        assertEquals("jpeg", reader.formatName.lowercase())
        assertEquals(6 to 9, imageDimensions(jpeg))
        assertEquals(false, ImageIO.read(ByteArrayInputStream(jpeg)).colorModel.hasAlpha())
    }

    @Test
    fun `an image whose header declares a huge canvas is not decoded`() {
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

        // IHDR starts at byte 16 with the width and height; the pixel data stays 1x1, so decoding would fail
        // late, after allocating the declared canvas.
        ByteBuffer.wrap(png).putInt(16, 50_000).putInt(20, 50_000)

        assertEquals(50_000 to 50_000, imageDimensions(png))
        assertNull(reencodedAsJpeg(png))
    }

    @Test
    fun `bytes that are no image are not re-encoded`() {
        assertNull(reencodedAsJpeg(byteArrayOf(1, 2, 3)))
    }
}
