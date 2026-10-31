package com.helltar.vusan.llm

import com.helltar.vusan.common.rethrowIfCancellation
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.network.sockets.*
import io.ktor.client.plugins.*
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// a server that tripped over this request, or a stream cut short: the same call a second later usually works.
private val REPEATABLE_STATUSES = setOf(500, 502, 503, 504)
private val REPEATABLE_BODY_REGEX = Regex("server_error|overloaded", RegexOption.IGNORE_CASE)

// what a repeat cannot fix under any status: a spent allowance names its deadline and goes to the fallback at
// once, and a refused request is refused again.
private val FINAL_BODY_REGEX =
    Regex(
        "usage_limit_reached|usage limit reached|usage_not_included|quota|" +
                "cyber_policy|content[_ ]policy|content[_ ]filter|invalid_prompt|was flagged",
        RegexOption.IGNORE_CASE,
    )

/**
 * Whether a call that failed with [failure] is worth repeating as is. Rate limits are left out on purpose: on
 * the subscription a `429` is the allowance running out, and elsewhere the fallback is faster. So are
 * timeouts, which already waited the whole request timeout; a dropped or refused connection is another
 * matter.
 */
internal fun isRepeatableFailure(failure: Throwable): Boolean {
    if (failure !is LlmException) return false
    if (failure.body?.let(FINAL_BODY_REGEX::containsMatchIn) == true) return false

    return failure.cutShort ||
            failure.status in REPEATABLE_STATUSES ||
            failure.body?.let(REPEATABLE_BODY_REGEX::containsMatchIn) == true ||
            (failure.status == null && failure.cause.isDroppedConnection())
}

private fun Throwable?.isDroppedConnection(): Boolean =
    generateSequence(this) { it.cause }.any {
        (it is IOException && it !is SocketTimeoutException && it !is HttpRequestTimeoutException) ||
                it.message?.contains("connection reset", ignoreCase = true) == true
    }

/**
 * The same client, making a call up to [maxAttempts] times on a passing provider failure. A model call
 * changes nothing on the provider until it answers, so a repeat is safe anywhere in a turn; only what
 * survives the repeats reaches the fallback or the person.
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
