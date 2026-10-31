package com.helltar.vusan.telegram

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.telegram.delivery.isTelegramUnavailable
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runInterruptible
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.generics.TelegramClient

// the async surface: await() suspends without holding a thread and surfaces cancellation as
// CancellationException, where the blocking path wraps the interrupt in a TelegramApiException and hides it
// from rethrowIfCancellation.
internal suspend fun <T> TelegramClient.api(block: TelegramClient.() -> CompletableFuture<T>): T =
    block().await()

private val log = KotlinLogging.logger("TelegramApi")

private val FIRST_OUTAGE_WAIT = 1.seconds
private val MAX_OUTAGE_WAIT = 1.minutes

/**
 * Sends [request] until it goes through, backing off while [isOutage] says the failure is telegram's state
 * rather than its answer. For requests a start cannot go on without: failing one ends the process, and every
 * restart repeats the startup checks, so an outage is better waited out in place. A refusal, a revoked token
 * above all, is not waited out.
 */
internal suspend fun <T> waitingOutTelegram(
    method: String,
    isOutage: (Throwable) -> Boolean = { it.isTelegramUnavailable() },
    request: suspend () -> T,
): T {
    var wait = FIRST_OUTAGE_WAIT

    while (true) {
        val outcome = runCatching { request() }
        val error = outcome.exceptionOrNull() ?: return outcome.getOrThrow()

        error.rethrowIfCancellation()
        if (!isOutage(error)) throw error

        log.warn { "Telegram cannot answer $method yet, asking again in ${wait.inWholeSeconds}s: ${error.message}" }
        delay(wait)
        wait = (wait * 2).coerceAtMost(MAX_OUTAGE_WAIT)
    }
}

/**
 * A file the Bot API served, with its server path. Telegram names the path after the real format
 * (`stickers/file_15.webp`, `photos/file_4.jpg`), often the only clue to what the bytes are: message metadata
 * has no name for a sticker or photo.
 */
internal class TelegramFile(val bytes: ByteArray, val path: String?)

// reading the stream is blocking io, so it runs on IO; runInterruptible frees the thread on cancellation (the
// http call is not aborted). named for its id argument: the client's member `downloadFile` takes a server
// path and would win over a same-named extension.
internal suspend fun TelegramClient.downloadFileById(fileId: String): TelegramFile {
    val file = api { executeAsync(GetFile.builder().fileId(fileId).build()) }
    val bytes = runInterruptible(Dispatchers.IO) { downloadFileAsStream(file).use { it.readBytes() } }

    return TelegramFile(bytes, file.filePath)
}

internal suspend fun TelegramClient.downloadFileBytes(fileId: String): ByteArray = downloadFileById(fileId).bytes
