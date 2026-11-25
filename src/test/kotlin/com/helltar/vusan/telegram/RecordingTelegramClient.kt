package com.helltar.vusan.telegram

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import org.telegram.telegrambots.meta.api.methods.botapimethods.PartialBotApiMethod
import org.telegram.telegrambots.meta.generics.TelegramClient

/** One Bot API call as it would go on the wire: the method name and its JSON body. */
internal data class RecordedCall(val method: String, val body: JsonNode)

/**
 * A client that records every call and answers it with [answer] for the method's name, so a test reads what
 * would have been sent without a server.
 */
internal fun recordingTelegramClient(
    calls: MutableList<RecordedCall>,
    answer: (method: String) -> Any = { true },
): TelegramClient {
    val mapper = JsonMapper()

    return Proxy.newProxyInstance(
        TelegramClient::class.java.classLoader,
        arrayOf(TelegramClient::class.java),
    ) { _, method, args ->
        when (method.name) {
            "executeAsync" -> {
                val call = args.single() as PartialBotApiMethod<*>

                synchronized(calls) { calls += RecordedCall(call.method, mapper.valueToTree(call)) }
                CompletableFuture.completedFuture(answer(call.method))
            }

            "toString" -> "RecordingTelegramClient"
            else -> error("unexpected call: ${method.name}")
        }
    } as TelegramClient
}
