package com.helltar.vusan.telegram

import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.message.SendMessageResult

// the calls a proxy fake of TelegramClient answers: requests go through executeAsync, and sends that may answer
// a button press through executeWithResultAsync (see `send`).
internal val FAKE_CLIENT_CALLS = setOf("executeAsync", "executeWithResultAsync")

// a fake builds one answer per request; a send expects its message wrapped, and a failure stays as it is.
internal fun CompletableFuture<Any>.answering(method: Method): CompletableFuture<Any> =
    if (method.name == "executeWithResultAsync") thenApply { SendMessageResult.message(it as Message) } else this
