package com.helltar.vusan.tools.images

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * A png of [width] by [height] showing scene number [scene]: the same scene at any size is one picture, and
 * two scenes are different pictures, as a picture hash sees them.
 */
internal fun testPicture(width: Int, height: Int, scene: Int = 0): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)

    image.createGraphics().apply {
        color = Color.WHITE
        fillRect(0, 0, width, height)
        color = Color.BLACK

        // bands whose count and offset depend on the scene, drawn in proportion to the canvas
        val bands = 2 + scene % 5

        for (band in 0 until bands step 2) {
            fillRect(width * band / bands, 0, width / bands, height * (1 + scene % 3) / 3)
        }

        fillOval(width * (scene % 4) / 5, height / 2, width / 4, height / 3)
        dispose()
    }

    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}
