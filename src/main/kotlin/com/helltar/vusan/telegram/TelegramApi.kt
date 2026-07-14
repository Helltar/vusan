package com.helltar.vusan.telegram

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.generics.TelegramClient

// telegrambots' okhttp client is blocking, so every call runs on the io dispatcher;
// runInterruptible lets coroutine cancellation interrupt an in-flight request instead of
// leaving it running detached.
internal suspend fun <T> TelegramClient.api(block: TelegramClient.() -> T): T =
    runInterruptible(Dispatchers.IO) { block() }

internal suspend fun TelegramClient.downloadFileBytes(fileId: String): ByteArray =
    api {
        val file = execute(GetFile.builder().fileId(fileId).build())
        downloadFileAsStream(file).use { it.readBytes() }
    }
