package com.helltar.vusan.tools.sandbox

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.tools.common.limitTo
import com.helltar.vusan.tools.common.sanitizeFilename
import com.helltar.vusan.tools.common.suspendToolGuard
import java.util.Base64

private const val MAX_OUTPUT_CHARS = 4_000
private const val MAX_ERROR_CHARS = 500
private const val MAX_PHOTOS = 10

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp")

@Suppress("unused")
class SandboxTools(private val client: SandboxClient, private val outbox: BotOutbox) : ToolSet {

    @Tool
    @LLMDescription(SandboxToolDescriptions.RUN_CODE)
    suspend fun runCode(
        @LLMDescription(SandboxToolDescriptions.RUN_CODE_SOURCE)
        code: String
    ): String = suspendToolGuard {
        require(code.isNotBlank()) { "Code must not be blank" }

        val result = client.run(code)

        if (result.timedOut) {
            return@suspendToolGuard "The code timed out before finishing. Simplify it or avoid long-running loops, then try again if it would help."
        }

        val animations = result.files.filter { it.name.isGifName() }.mapNotNull { it.toAnimation() }
        val (imageFiles, otherFiles) = result.files.filterNot { it.name.isGifName() }.partition { it.name.isImageName() }
        val photos = imageFiles.mapNotNull { it.toPhoto() }
        val documents = otherFiles.mapNotNull { it.toDocument() }

        animations.forEach { outbox.enqueue(it) }
        when {
            photos.size == 1 -> outbox.enqueue(photos.single())
            photos.size >= 2 -> outbox.enqueue(BotOutput.PhotoGroup(photos.take(MAX_PHOTOS)))
        }
        documents.forEach { outbox.enqueue(it) }

        buildString {
            if (!result.ok) {
                appendLine("The code raised an error: ${result.error.lastMeaningfulLine().limitTo(MAX_ERROR_CHARS)}")
            }

            result.stdout.trim().takeIf { it.isNotEmpty() }?.let {
                appendLine("<stdout>")
                appendLine(it.limitTo(MAX_OUTPUT_CHARS))
                appendLine("</stdout>")
            }

            result.stderr.trim().takeIf { it.isNotEmpty() }?.let {
                appendLine("<stderr>")
                appendLine(it.limitTo(MAX_OUTPUT_CHARS))
                appendLine("</stderr>")
            }

            val sent = animations.map { it.filename } + photos.map { it.filename } + documents.map { it.filename }
            if (sent.isNotEmpty()) {
                appendLine("Delivered ${sent.size} file(s) to the chat: ${sent.joinToString(", ")}. Comment on the result for the user; do not paste the file contents.")
            }
        }.trim().ifBlank { "The code ran successfully but produced no output. Use print(...) to return values." }
    }

    private fun SandboxFile.toAnimation(): BotOutput.Animation? =
        decodedBytes()?.let { BotOutput.Animation(bytes = it, filename = name.sanitizeFilename().ifBlank { "animation.gif" }) }

    private fun SandboxFile.toPhoto(): BotOutput.Photo? =
        decodedBytes()?.let { BotOutput.Photo(bytes = it, filename = name.sanitizeFilename().ifBlank { "chart.png" }) }

    private fun SandboxFile.toDocument(): BotOutput.Document? =
        decodedBytes()?.let { BotOutput.Document(bytes = it, filename = name.sanitizeFilename().ifBlank { "output" }) }

    private fun SandboxFile.decodedBytes(): ByteArray? =
        runCatching { Base64.getDecoder().decode(base64) }.getOrNull()?.takeIf { it.isNotEmpty() }
}

private fun String.isImageName(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun String.isGifName(): Boolean =
    substringAfterLast('.', "").equals("gif", ignoreCase = true)

private fun String.lastMeaningfulLine(): String =
    trim().lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: "unknown error"
