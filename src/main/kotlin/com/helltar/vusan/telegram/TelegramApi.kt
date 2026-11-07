package com.helltar.vusan.telegram

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.telegram.delivery.isTelegramUnavailable
import com.helltar.vusan.request.PlatformLimits
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runInterruptible
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.fileSize
import kotlin.io.path.readBytes
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

/**
 * A file a local Bot API server holds that is past what the bot reads in. Telegram's own server refuses such a
 * file in `getFile` already; a local one serves any size, so the bot holds the line itself.
 */
internal class LocalFileTooBigException(path: String, sizeBytes: Long) :
    IOException("file is too big: [$path] is $sizeBytes bytes")

// reading the stream is blocking io, so it runs on IO; runInterruptible frees the thread on cancellation (the
// http call is not aborted). named for its id argument: the client's member `downloadFile` takes a server
// path and would win over a same-named extension.
//
// a local bot api server answers getFile with the absolute path of the file on its own disk and serves no
// download, so the file is read from there: the bot mounts the server's directory at the same path. telegram's
// own server answers with a relative path, which is downloaded as before.
internal suspend fun TelegramClient.downloadFileById(fileId: String): TelegramFile {
    val file = api { executeAsync(GetFile.builder().fileId(fileId).build()) }
    val localPath = file.filePath?.takeIf { it.startsWith('/') }?.let(::Path)

    val bytes =
        runInterruptible(Dispatchers.IO) {
            if (localPath != null) readLocalFile(localPath)
            else downloadFileAsStream(file).use { it.readBytes() }
        }

    return TelegramFile(bytes, file.filePath)
}

private fun readLocalFile(path: Path): ByteArray {
    val size = path.fileSize()

    if (size > PlatformLimits.TELEGRAM_LOCAL_SERVER.downloadBytes) throw LocalFileTooBigException(path.toString(), size)

    return path.readBytes()
}

internal suspend fun TelegramClient.downloadFileBytes(fileId: String): ByteArray = downloadFileById(fileId).bytes
