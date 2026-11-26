package com.helltar.vusan.common

import com.helltar.vusan.tools.images.testPicture
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `one picture hashes alike at any size and in another format, two pictures do not`() {
        val large = assertNotNull(pictureHash(testPicture(1200, 800, scene = 1)))
        val small = assertNotNull(pictureHash(testPicture(300, 200, scene = 1)))
        val jpeg = assertNotNull(pictureHash(assertNotNull(reencodedAsJpeg(testPicture(640, 427, scene = 1)))))
        val other = assertNotNull(pictureHash(testPicture(1200, 800, scene = 2)))

        // jpeg noise flips a few of the comparisons between flat neighbors: 5 bits here
        assertTrue((large xor small).countOneBits() <= 2)
        assertTrue((large xor jpeg).countOneBits() <= 8)
        assertTrue((large xor other).countOneBits() > 16)
    }

    @Test
    fun `bytes that are no image have no picture hash`() {
        assertNull(pictureHash("not an image".toByteArray()))
    }

    @Test
    fun `an image longer than telegram shows a photo is re-encoded at its size`() {
        val jpeg = assertNotNull(reencodedAsJpeg(testPicture(5200, 2600, scene = 1)))
        val (width, height) = assertNotNull(imageDimensions(jpeg))

        assertTrue(width in 1_700..2_560, "width=$width")
        assertEquals(width / 2, height, "the proportions stay")
    }

    @Test
    fun `a webp picture is read, measured, hashed and re-encoded`() {
        val webp = Base64.getDecoder().decode(TINY_WEBP)

        assertEquals(64 to 48, imageDimensions(webp))
        assertNotNull(pictureHash(webp))
        assertEquals(64 to 48, imageDimensions(assertNotNull(reencodedAsJpeg(webp))))
    }

    // a lossless webp decodes into a whole raster before it is subsampled: 4100x4100, one color, is 16.8
    // megapixels in 712 bytes
    @Test
    fun `a webp past what a whole raster may take is measured but not decoded`() {
        val webp = checkNotNull(javaClass.getResource("/images/solid-4100x4100-lossless.webp")).readBytes()

        assertEquals(4_100 to 4_100, imageDimensions(webp))
        assertNull(reencodedAsJpeg(webp))
        assertNull(pictureHash(webp))
    }

    // libjpeg keeps a progressive jpeg's coefficients for every pixel until its last scan, so the header alone may
    // ask for hundreds of megabytes off the heap
    @Test
    fun `a progressive jpeg past what a whole image may take is measured but not decoded`() {
        val progressive = jpegDeclaring(5_000, 5_000, progressive = true)

        assertEquals(5_000 to 5_000, imageDimensions(progressive))
        assertNull(reencodedAsJpeg(progressive))
        assertNull(pictureHash(progressive))
    }

    @Test
    fun `a baseline jpeg of the same canvas is still decoded a row at a time`() {
        assertNotNull(pictureHash(jpegDeclaring(5_000, 5_000, progressive = false)))
    }

    @Test
    fun `a small progressive jpeg is decoded`() {
        val progressive = jpegDeclaring(64, 48, progressive = true)

        assertEquals(64 to 48, imageDimensions(assertNotNull(reencodedAsJpeg(progressive))))
    }

    @Test
    fun `this runtime reads and writes jpeg and reads webp`() {
        assertEquals(mapOf("jpeg" to true, "webp" to true), workingImageCodecs())
    }

    // a 64x48 jpeg whose frame header is rewritten to declare the given canvas, so the reader sees that size while
    // the test never builds it
    private fun jpegDeclaring(width: Int, height: Int, progressive: Boolean): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val output = ByteArrayOutputStream()

        ImageIO.createImageOutputStream(output).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam.apply { if (progressive) progressiveMode = ImageWriteParam.MODE_DEFAULT }
            writer.write(null, IIOImage(testPictureImage(), null, null), param)
        }

        writer.dispose()

        val bytes = output.toByteArray()
        val frameMarker = if (progressive) 0xC2 else 0xC0
        val frame = (0 until bytes.size - 1).first { bytes[it] == 0xFF.toByte() && bytes[it + 1] == frameMarker.toByte() }

        // after the marker: length (2 bytes), precision (1), then height and width (2 each)
        ByteBuffer.wrap(bytes).putShort(frame + 5, height.toShort()).putShort(frame + 7, width.toShort())

        return bytes
    }

    private fun testPictureImage(): BufferedImage = ImageIO.read(ByteArrayInputStream(testPicture(64, 48, scene = 1)))

    private companion object {
        // 64x48, lossy vp8, made with imagemagick
        const val TINY_WEBP =
            "UklGRu4AAABXRUJQVlA4IOIAAADwCACdASpAADAAPm0qkEWkIq2WptjYBsS0AF8d4XyX8gOcy314AMsE/c7aOJ2vAACURJnTxblrK+9kE6CYzeiF" +
                "AiRkfwWuHriW7xcipZNygAD+8puAT93jhW/yU7Qqn//CM/wjP6+i4SIOC9mDths1VuTSp0OoZyNEkO7IBRuFErhNDq9AVzmPExGe48NEXvBG" +
                "bj6EGzCGl6ojR83w6Uo7RIy9GYpZEwkMWWJ32HZb4WpRLbOzNUXd/9TkyxAcSBWZ+yF8GiuQRVNGHMjNgm+7LeD4msPTfGbyMNuNKmJAAAAA"
    }
}
