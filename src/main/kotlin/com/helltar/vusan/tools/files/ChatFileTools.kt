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
import org.telegram.telegrambots.meta.generics.TelegramClient

private const val MAX_FILE_ID_CHARS = 200
private const val DEFAULT_CHAT_FILE_NAME = "file"

/** Telegram serves bots the files it stores only up to this size, whatever the chat could upload. */
internal const val MAX_TELEGRAM_FILE_MB = 20

/**
 * Resending a file the platform already holds, by the id it holds it under.
 *
 * A `file_id` is Telegram's own model — it names a file inside one bot's view of one chat, and no
 * other messenger has the concept — so this is separate from [FileTools], whose sending and public
 * downloading work anywhere.
 */
@Suppress("unused")
class ChatFileTools(
    private val telegram: TelegramClient,
    private val outbox: BotOutbox
) : ToolSet {

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
