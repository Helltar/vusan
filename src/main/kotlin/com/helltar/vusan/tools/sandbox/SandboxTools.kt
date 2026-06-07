package com.helltar.vusan.tools.sandbox

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.common.limitTo
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.common.sanitizeFilename
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.tools.suspendToolGuard
import java.util.*

private const val MAX_OUTPUT_CHARS = 4_000
private const val MAX_ERROR_CHARS = 500
private const val MAX_MEDIA_GROUP = 10
private const val MAX_INPUT_FILE_BYTES = 10 * 1024 * 1024

// Only surface run time when it's meaningful — below this it's noise the model would parrot.
private const val SLOW_RUN_MS = 1_000

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp")

@Suppress("unused")
class SandboxTools(
    private val client: SandboxClient,
    private val outbox: BotOutbox,
    private val attachedFile: AttachedFile? = null
) : ToolSet {

    @Tool
    @LLMDescription(SandboxToolDescriptions.CODE_EXECUTION)
    suspend fun codeExecution(
        @LLMDescription(SandboxToolDescriptions.CODE_EXECUTION_SOURCE)
        code: String
    ): String = suspendToolGuard {
        val input = loadInputFile()
        val result = client.run(code, input?.file?.let(::listOf).orEmpty())

        if (result.timedOut) {
            return@suspendToolGuard "The code timed out before finishing. " +
                    "Simplify it or avoid long-running loops, then try again if it would help."
        }

        val animations = result.files.filter { it.name.isGifName() }.mapNotNull { it.toAnimation() }

        val (imageFiles, otherFiles) =
            result.files.filterNot { it.name.isGifName() }.partition { it.name.isImageName() }

        val photos = imageFiles.mapNotNull { it.toPhoto() }
        val imageDocuments = imageFiles.mapNotNull { it.toDocument() }
        val documents = otherFiles.mapNotNull { it.toDocument() }

        animations.forEach { outbox.enqueue(it) }

        when {
            photos.size == 1 -> outbox.enqueue(photos.single())
            photos.size >= 2 -> outbox.enqueue(BotOutput.PhotoGroup(photos.take(MAX_MEDIA_GROUP)))
        }

        // Telegram recompresses inline photos to JPEG, softening chart text. Send each image again as
        // an uncompressed document so a pixel-perfect copy is available alongside the inline preview.
        enqueueDocuments(imageDocuments + documents)

        val body =
            buildString {
                input?.note?.let { appendLine(it) }
                skippedFilesNote(result.skipped)?.let { appendLine(it) }

                if (!result.ok) {
                    appendLine(
                        "The code raised an error: ${
                            result.error.lastMeaningfulLine().limitTo(MAX_ERROR_CHARS)
                        }"
                    )
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
                    appendLine(
                        "Delivered ${sent.size} file(s) to the chat: ${sent.joinToString(", ")}. " +
                                "Comment on the result for the user; do not paste the file contents."
                    )
                }
            }.trim().ifBlank { "The code ran successfully but produced no output. Use print(...) to return values." }

        result.elapsedMs.takeIf { it >= SLOW_RUN_MS }
            ?.let { "$body\n\nComputed in ${"%.1f".format(Locale.ROOT, it / 1000.0)}s." }
            ?: body
    }

    private suspend fun loadInputFile(): InputFileResult? {
        val file = attachedFile ?: return null

        if (file.fileSizeBytes != null && file.fileSizeBytes > MAX_INPUT_FILE_BYTES) {
            return InputFileResult(
                null,
                "The attached file `${file.name}` is too large for the sandbox (limit 10 MB), so it was not loaded."
            )
        }

        val bytes =
            runCatching { file.loadBytes() }
                .getOrElse {
                    it.rethrowIfCancellation()
                    return InputFileResult(
                        null,
                        "The attached file `${file.name}` could not be downloaded, so it was not loaded."
                    )
                }

        if (bytes.size > MAX_INPUT_FILE_BYTES) {
            return InputFileResult(
                null,
                "The attached file `${file.name}` is too large for the sandbox (limit 10 MB), so it was not loaded."
            )
        }

        return InputFileResult(SandboxFile(name = file.name, base64 = Base64.getEncoder().encodeToString(bytes)), null)
    }

    private class InputFileResult(val file: SandboxFile?, val note: String?)

    // Deliver documents as an album (one message) instead of one message per file; a lone document goes on its own.
    private fun enqueueDocuments(documents: List<BotOutput.Document>) {
        documents.chunked(MAX_MEDIA_GROUP).forEach { chunk ->
            if (chunk.size == 1) outbox.enqueue(chunk.single()) else outbox.enqueue(BotOutput.DocumentGroup(chunk))
        }
    }

    private fun SandboxFile.toAnimation(): BotOutput.Animation? =
        decodedBytes()?.let {
            BotOutput.Animation(bytes = it, filename = name.sanitizeFilename().ifBlank { "animation.gif" })
        }

    private fun SandboxFile.toPhoto(): BotOutput.Photo? =
        decodedBytes()?.let {
            BotOutput.Photo(
                bytes = it,
                filename = name.sanitizeFilename().ifBlank { "chart.png" },
                fallbackToDocument = false
            )
        }

    private fun SandboxFile.toDocument(): BotOutput.Document? =
        decodedBytes()?.let {
            BotOutput.Document(bytes = it, filename = name.sanitizeFilename().ifBlank { "output" })
        }

    private fun SandboxFile.decodedBytes(): ByteArray? =
        runCatching { Base64.getDecoder().decode(base64) }.getOrNull()?.takeIf { it.isNotEmpty() }
}

// Files the sandbox produced but could not return (over the per-file size cap or the per-run file count).
// Surfaced to the model so it can tell the user instead of silently claiming success.
private fun skippedFilesNote(skipped: List<SkippedFile>): String? {
    if (skipped.isEmpty()) return null

    return buildString {
        appendLine("Some files were produced but NOT delivered to the chat:")
        skipped.forEach { appendLine("- `${it.name}` (${formatBytes(it.bytes)}): ${skipReason(it.reason)}") }
        append("Tell the user; if a file was meant to be sent, regenerate it smaller (lower resolution/quality).")
    }
}

private fun skipReason(reason: String): String =
    when (reason) {
        "too_large" -> "too large to send"
        "too_many" -> "skipped — too many output files in one run"
        else -> reason
    }

private fun formatBytes(bytes: Long): String =
    if (bytes >= 1024 * 1024) "%.1f MB".format(Locale.ROOT, bytes / (1024.0 * 1024))
    else "%.0f KB".format(Locale.ROOT, bytes / 1024.0)

private fun String.isImageName(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun String.isGifName(): Boolean =
    substringAfterLast('.', "").equals("gif", ignoreCase = true)

private fun String.lastMeaningfulLine(): String =
    trim().lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: "unknown error"
