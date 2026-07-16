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

// reading the download stream is blocking io, so it runs on the io dispatcher; runInterruptible
// releases the thread on cancellation (the http call itself is not aborted).
internal suspend fun TelegramClient.downloadFileBytes(fileId: String): ByteArray {
    val file = api { executeAsync(GetFile.builder().fileId(fileId).build()) }
    return runInterruptible(Dispatchers.IO) { downloadFileAsStream(file).use { it.readBytes() } }
}
