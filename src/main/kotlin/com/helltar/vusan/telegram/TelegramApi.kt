package com.helltar.vusan.telegram

import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runInterruptible
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.generics.TelegramClient

// api calls go through the client's async surface: await() suspends without holding a thread and
// surfaces cancellation as CancellationException. the blocking execute path would wrap the
// interrupt into a TelegramApiException, hiding the cancellation from rethrowIfCancellation.
internal suspend fun <T> TelegramClient.api(block: TelegramClient.() -> CompletableFuture<T>): T =
    block().await()

/**
 * A file the Bot API served, with the server-side path it came from. Telegram names that path after
 * the real format (`stickers/file_15.webp`, `photos/file_4.jpg`) and it is often the only thing that
 * says what the bytes are — the message metadata carries no name for a sticker or a photo.
 */
internal class TelegramFile(val bytes: ByteArray, val path: String?)

// reading the download stream is blocking io, so it runs on the io dispatcher; runInterruptible
// releases the thread on cancellation (the http call itself is not aborted). named for the id it
// takes: the client's own `downloadFile` is a member taking a server path, and would win over an
// extension of the same name.
internal suspend fun TelegramClient.downloadFileById(fileId: String): TelegramFile {
    val file = api { executeAsync(GetFile.builder().fileId(fileId).build()) }
    val bytes = runInterruptible(Dispatchers.IO) { downloadFileAsStream(file).use { it.readBytes() } }

    return TelegramFile(bytes, file.filePath)
}

internal suspend fun TelegramClient.downloadFileBytes(fileId: String): ByteArray = downloadFileById(fileId).bytes
