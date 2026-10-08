package com.helltar.vusan.llm

import com.helltar.vusan.common.rethrowIfCancellation
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// a server that tripped over this one request, or a stream that was cut before it finished: the same
// call a second later usually goes through.
private val REPEATABLE_STATUSES = setOf(500, 502, 503, 504)
private val REPEATABLE_BODY_REGEX =
    Regex("server_error|overloaded|stream ended without a completed response", RegexOption.IGNORE_CASE)

// what a repeat cannot fix, whatever status it came with: a spent allowance names its own deadline and
// belongs to the fallback at once, and a refused request is refused again.
private val FINAL_BODY_REGEX =
    Regex(
        "usage_limit_reached|usage limit reached|usage_not_included|quota|" +
                "cyber_policy|content[_ ]policy|content[_ ]filter|invalid_prompt|was flagged",
        RegexOption.IGNORE_CASE,
    )

/**
 * Whether a call that failed with [failure] is worth making again as it is.
 *
 * A rate limit is left out on purpose: on the subscription a `429` is the allowance running out, and
 * elsewhere the fallback provider is the faster way round it. So is a timeout — the call already waited
 * the whole request timeout once; a connection that dropped or was refused is another matter.
 */
internal fun isRepeatableFailure(failure: Throwable): Boolean {
    if (failure !is LlmException) return false
    if (failure.body?.let(FINAL_BODY_REGEX::containsMatchIn) == true) return false

    return failure.status in REPEATABLE_STATUSES ||
            failure.body?.let(REPEATABLE_BODY_REGEX::containsMatchIn) == true ||
            failure.status == null && failure.cause.isDroppedConnection()
}

private fun Throwable?.isDroppedConnection(): Boolean =
    generateSequence(this) { it.cause }.any {
        it is IOException && it !is io.ktor.client.network.sockets.SocketTimeoutException ||
                it is java.net.ConnectException ||
                it.message?.contains("connection reset", ignoreCase = true) == true
    }

/**
 * The same client, making a call up to [maxAttempts] times when the provider's failure is a passing one.
 *
 * A model call changes nothing on the provider's side until it answers, so a repeat is safe wherever in
 * a turn it happens. Only what survives the repeats reaches the fallback provider or the person.
 */
class RetryingLlmClient(
    private val delegate: LlmClient,
    private val maxAttempts: Int = DEFAULT_ATTEMPTS,
    private val initialDelay: Duration = 1.seconds,
) : LlmClient {

    override suspend fun complete(model: LlmModel, request: ChatRequest): Reply {
        var attempt = 1

        while (true) {
            try {
                return delegate.complete(model, request)
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                if (attempt >= maxAttempts || !isRepeatableFailure(e)) throw e

                log.warn { "model call failed, attempt $attempt of $maxAttempts: ${e.message?.lineSequence()?.firstOrNull()}" }
                delay(initialDelay * attempt)
                attempt++
            }
        }
    }

    override fun close() = delegate.close()

    private companion object {
        const val DEFAULT_ATTEMPTS = 3
        val log = KotlinLogging.logger {}
    }
}
