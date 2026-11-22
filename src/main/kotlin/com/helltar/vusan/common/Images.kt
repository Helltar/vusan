package com.helltar.vusan.common

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

private const val JPEG_QUALITY = 0.92f

// the image is decoded whole, and a few kilobytes of png can declare a canvas that fills the heap, so the size
// is read from the header first. telegram takes no photo whose sides add up past 10000 anyway.
private const val REENCODE_MAX_SIDES = 10_000
private const val REENCODE_MAX_PIXELS = 16_000_000L

/**
 * An encoded image's pixel size, or `null` when unreadable. Only the header is decoded, so large files stay
 * cheap.
 */
internal fun imageDimensions(bytes: ByteArray): Pair<Int, Int>? =
    runCatching {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) return@use null

            val reader = readers.next()

            try {
                reader.input = stream
                reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()

/**
 * The image re-encoded as a plain 8-bit RGB JPEG at its own size, or `null` when it cannot be decoded.
 * Transparency is flattened onto white, and color profiles, extra channels and bit depths are dropped.
 * `null` too for an image too large to decode safely.
 */
internal fun reencodedAsJpeg(bytes: ByteArray): ByteArray? {
    val (width, height) = imageDimensions(bytes) ?: return null
    if (width + height > REENCODE_MAX_SIDES || width.toLong() * height > REENCODE_MAX_PIXELS) return null

    val source = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null

    // the jpeg writer refuses an alpha channel, so the picture is drawn onto an opaque canvas first
    val canvas = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)

    canvas.createGraphics().apply {
        drawImage(source, 0, 0, Color.WHITE, null)
        dispose()
    }

    val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull() ?: return null
    val output = ByteArrayOutputStream()

    try {
        ImageIO.createImageOutputStream(output).use { stream ->
            writer.output = stream

            val param =
                writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = JPEG_QUALITY
                }

            writer.write(null, IIOImage(canvas, null, null), param)
        }
    } finally {
        writer.dispose()
    }

    return output.toByteArray()
}
