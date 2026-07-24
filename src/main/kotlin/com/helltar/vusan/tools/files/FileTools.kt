package com.helltar.vusan.tools.files

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.common.sanitizeFilename
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard
import java.util.Locale

private const val MAX_URL_CHARS = 2_000
private const val BYTES_PER_KB = 1024.0
private const val BYTES_PER_MB = BYTES_PER_KB * 1024

@Suppress("unused")
class FileTools(private val downloads: FileDownloadClient, private val outbox: BotOutbox) : ToolSet {

    @Tool
    @LLMDescription(FileToolDescriptions.SEND_FILE)
    suspend fun sendFile(
        @LLMDescription(FileToolDescriptions.CONTENT)
        content: String,
        @LLMDescription(FileToolDescriptions.FILENAME)
        filename: String
    ): String = suspendToolGuard {
        require(content.isNotEmpty()) { "File content must not be empty" }

        val safeName = filename.sanitizeFilename().ifBlank { "file.txt" }

        outbox.enqueue(BotOutput.Document(bytes = content.toByteArray(Charsets.UTF_8), filename = safeName))

        """File "$safeName" ready (${content.length} chars) and will be sent."""
    }

    @Tool
    @LLMDescription(FileToolDescriptions.DOWNLOAD_FILE)
    suspend fun downloadFile(
        @LLMDescription(FileToolDescriptions.DOWNLOAD_URL)
        url: String,
        @LLMDescription(FileToolDescriptions.DOWNLOAD_FILENAME)
        filename: String = ""
    ): String = suspendToolGuard {
        val target = url.requireToolText("Download URL", MAX_URL_CHARS)

        when (val result = downloads.download(target, filename)) {
            is FileDownloadResult.Success -> {
                outbox.enqueue(BotOutput.Document(bytes = result.bytes, filename = result.filename))

                """Downloaded "${result.filename}" (${result.bytes.size.toLong().asFileSize()}) and it will be sent."""
            }

            is FileDownloadResult.TooLarge -> {
                val size = result.sizeBytes?.let { "is ${it.asFileSize()}" } ?: "is larger than $MAX_DOWNLOAD_MB MB"

                "The file at $target $size, above the $MAX_DOWNLOAD_MB MB Telegram upload limit for bots. " +
                        "Tell the user it is too large to send and give them the direct link instead."
            }
        }
    }
}

private fun Long.asFileSize(): String =
    if (this >= BYTES_PER_MB)
        String.format(Locale.ROOT, "%.1f MB", this / BYTES_PER_MB)
    else
        String.format(Locale.ROOT, "%.0f KB", this / BYTES_PER_KB)
