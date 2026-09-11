package com.helltar.vusan.config

import ai.koog.http.client.KoogHttpClient
import kotlinx.serialization.json.Json

/**
 * The transport every OpenAI client here goes through, reading replies leniently: an enum value koog
 * has no constant for becomes that field's default instead of failing the whole reply.
 *
 * A Responses API reply repeats the reasoning config it was sent, and koog parses that echo with its
 * own `ReasoningEffort`, which stops at `high` — so a turn sent at `xhigh` or `max` failed only after
 * the model had answered. Koog never reads the echo; it takes the output items and the usage.
 */
internal class LenientDecodingHttpClientFactory(
    private val delegate: KoogHttpClient.Factory
) : KoogHttpClient.Factory {

    override fun create(
        clientName: String,
        baseUrl: String,
        headers: Map<String, String>,
        queryParameters: Map<String, String>,
        requestTimeoutMillis: Long,
        connectTimeoutMillis: Long,
        socketTimeoutMillis: Long,
        json: Json
    ): KoogHttpClient =
        delegate.create(
            clientName = clientName,
            baseUrl = baseUrl,
            headers = headers,
            queryParameters = queryParameters,
            requestTimeoutMillis = requestTimeoutMillis,
            connectTimeoutMillis = connectTimeoutMillis,
            socketTimeoutMillis = socketTimeoutMillis,
            json = Json(json) { coerceInputValues = true }
        )
}
