package com.helltar.vusan.common

import java.awt.Color
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

private const val JPEG_QUALITY = 0.92f

// a few kilobytes of png can declare a canvas no photo has, and decoding walks every declared pixel even when
// only some are kept, so the size is read from the header first. 64 megapixels is past any camera's photo.
private const val DECODE_MAX_PIXELS = 64_000_000L

// telegram shows a photo at most this large on its long side, and scales a larger one down itself
private const val REENCODE_MAX_LONG_SIDE = 2560

// enough pixels for a hash to see the picture's shape, few enough to decode cheaply
private const val HASH_DECODE_LONG_SIDE = 256

// a difference hash compares each of 8 rows of 9 shrunk pixels with its right neighbor, one bit per pair
private const val HASH_COLUMNS = 9
private const val HASH_ROWS = 8

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
 * The image re-encoded as a plain 8-bit RGB JPEG, or `null` when it cannot be decoded safely. Transparency is
 * flattened onto white, and color profiles, extra channels and bit depths are dropped. An image longer than
 * Telegram shows a photo is decoded at a fraction of its pixels, so it arrives no larger than Telegram would
 * make it.
 */
internal fun reencodedAsJpeg(bytes: ByteArray): ByteArray? {
    val source = decodedWithin(bytes, REENCODE_MAX_LONG_SIDE) ?: return null

    // the jpeg writer refuses an alpha channel, so the picture is drawn onto an opaque canvas first
    val canvas = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)

    canvas.createGraphics().apply {
        drawImage(source, 0, 0, Color.WHITE, null)
        dispose()
    }

    return jpegOf(canvas)
}

/**
 * A 64-bit difference hash of the picture: two encodings of one photo, at other sizes or qualities, differ in
 * a few bits, two different photos in many. `null` when it cannot be decoded safely.
 */
internal fun pictureHash(bytes: ByteArray): Long? {
    val source = decodedWithin(bytes, HASH_DECODE_LONG_SIDE) ?: return null

    // averaging rather than the default nearest pixel, which would hash the noise of whichever pixels it hit
    val shrunk = BufferedImage(HASH_COLUMNS, HASH_ROWS, BufferedImage.TYPE_BYTE_GRAY)

    shrunk.createGraphics().apply {
        drawImage(source.getScaledInstance(HASH_COLUMNS, HASH_ROWS, Image.SCALE_AREA_AVERAGING), 0, 0, null)
        dispose()
    }

    var hash = 0L

    for (y in 0 until HASH_ROWS) {
        for (x in 0 until HASH_COLUMNS - 1) {
            if (shrunk.raster.getSample(x, y, 0) > shrunk.raster.getSample(x + 1, y, 0)) {
                hash = hash or (1L shl (y * (HASH_COLUMNS - 1) + x))
            }
        }
    }

    return hash
}

/**
 * Which formats imageio both reads and, for jpeg, writes here: a runtime can carry the classes without the
 * native codec they load, and then fails only when a picture is decoded.
 */
internal fun workingImageCodecs(): Map<String, Boolean> {
    val sample = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
    val jpeg = runCatching { jpegOf(sample)?.let { decodedWithin(it, 2) } != null }.getOrDefault(false)
    val webp = ImageIO.getImageReadersByFormatName("webp").hasNext()

    return mapOf("jpeg" to jpeg, "webp" to webp)
}

/**
 * The image decoded with its long side at most [maxLongSide]: a larger one is read every n-th pixel, so its
 * full canvas is never allocated. `null` when it is unreadable or declares more than [DECODE_MAX_PIXELS].
 */
private fun decodedWithin(bytes: ByteArray, maxLongSide: Int): BufferedImage? =
    runCatching {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { stream ->
            val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return@use null

            try {
                reader.input = stream
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)

                if (width <= 0 || height <= 0 || width.toLong() * height > DECODE_MAX_PIXELS) return@use null

                val step = (maxOf(width, height) + maxLongSide - 1) / maxLongSide
                val param = reader.defaultReadParam.apply { if (step > 1) setSourceSubsampling(step, step, 0, 0) }

                reader.read(0, param)
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()

private fun jpegOf(image: BufferedImage): ByteArray? {
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

            writer.write(null, IIOImage(image, null, null), param)
        }
    } finally {
        writer.dispose()
    }

    return output.toByteArray()
}
