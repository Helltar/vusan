package com.helltar.vusan.common

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * The pixel size of an encoded image, or `null` when nothing here can read it. Only the header is
 * decoded, so this stays cheap on large files.
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
