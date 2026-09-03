package com.helltar.vusan.tools.files

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.common.sanitizeFilename
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.telegram.delivery.isFileTooBig
import com.helltar.vusan.telegram.delivery.isWrongFileIdentifier
import com.helltar.vusan.telegram.downloadFileById
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard
import java.util.Locale
import org.telegram.telegrambots.meta.generics.TelegramClient

private const val MAX_URL_CHARS = 2_000
private const val MAX_FILE_ID_CHARS = 200
private const val BYTES_PER_KB = 1024.0
private const val BYTES_PER_MB = BYTES_PER_KB * 1024
private const val DEFAULT_CHAT_FILE_NAME = "file"

/** Telegram serves bots the files it stores only up to this size, whatever the chat could upload. */
internal const val MAX_TELEGRAM_FILE_MB = 20

@Suppress("unused")
class FileTools(
    private val downloads: FileDownloadClient,
    private val telegram: TelegramClient,
    private val outbox: BotOutbox
) : ToolSet {

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

    @Tool
    @LLMDescription(FileToolDescriptions.SEND_CHAT_FILE)
    suspend fun sendChatFile(
        @LLMDescription(FileToolDescriptions.CHAT_FILE_ID)
        fileId: String,
        @LLMDescription(FileToolDescriptions.CHAT_FILENAME)
        filename: String = ""
    ): String = suspendToolGuard {
        val id = fileId.requireToolText("File id", MAX_FILE_ID_CHARS)

        val file =
            runCatching { telegram.downloadFileById(id) }
                .getOrElse { error ->
                    error.rethrowIfCancellation()

                    return@suspendToolGuard when {
                        error.isFileTooBig() ->
                            "Telegram serves bots files of at most $MAX_TELEGRAM_FILE_MB MB and this one is larger, " +
                                    "so it cannot be fetched at all. Tell the user that."

                        error.isWrongFileIdentifier() ->
                            "Telegram does not know file_id=[$id]. Pass a `file_id` exactly as it appears in the " +
                                    "metadata of the message holding the file, never a `file_unique_id` or a guess."

                        else -> throw error
                    }
                }

        val name = chatFilename(filename, file.path)

        outbox.enqueue(BotOutput.Document(bytes = file.bytes, filename = name))

        """Downloaded "$name" (${file.bytes.size.toLong().asFileSize()}) from Telegram and it will be sent."""
    }
}

// the model rarely has a name to pass — a sticker and a photo carry none — so telegram's own path
// (`stickers/file_15.webp`) names the file, and at minimum lends its extension to a bare name.
private fun chatFilename(requested: String, telegramPath: String?): String {
    val served = telegramPath?.substringAfterLast('/').orEmpty().sanitizeFilename()
    val name = requested.sanitizeFilename().ifBlank { served }.ifBlank { DEFAULT_CHAT_FILE_NAME }

    if (name.hasFileExtension) return name

    val extension = served.substringAfterLast('.', "")

    return if (extension.isEmpty()) name else "$name.$extension"
}

private fun Long.asFileSize(): String =
    if (this >= BYTES_PER_MB)
        String.format(Locale.ROOT, "%.1f MB", this / BYTES_PER_MB)
    else
        String.format(Locale.ROOT, "%.0f KB", this / BYTES_PER_KB)
