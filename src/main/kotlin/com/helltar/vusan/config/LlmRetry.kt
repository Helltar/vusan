package com.helltar.vusan.config

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.retry.RetryConfig
import ai.koog.prompt.executor.clients.retry.RetryablePattern
import ai.koog.prompt.executor.clients.retry.RetryingLLMClient
import kotlin.time.Duration.Companion.seconds

// a server that tripped over this one request, or a stream that was cut before it finished: the same
// call a second later usually goes through. koog folds the status and the error body into the message,
// so this is read out of the message like every other provider failure.
private val REPEATABLE_FAILURE_REGEX =
    Regex(
        """Status code:\s*(500|502|503|504)\b|server_error|overloaded|""" +
                "connection reset|connection refused|stream ended without a completed response",
        RegexOption.IGNORE_CASE,
    )

// what a repeat cannot fix, whatever status it came with: a spent allowance names its own deadline and
// belongs to the fallback at once, and a refused request is refused again.
private val FINAL_FAILURE_REGEX =
    Regex(
        "usage_limit_reached|usage limit reached|usage_not_included|quota|" +
                "cyber_policy|content[_ ]policy|content[_ ]filter|invalid_prompt|was flagged",
        RegexOption.IGNORE_CASE,
    )

/**
 * Whether a call that failed with [message] is worth making again as it is.
 *
 * A rate limit is left out on purpose: on the subscription a `429` is the allowance running out, and
 * elsewhere the fallback provider is the faster way round it. So is a timeout — the call already
 * waited the whole request timeout once.
 */
internal fun isRepeatableFailure(message: String): Boolean =
    REPEATABLE_FAILURE_REGEX.containsMatchIn(message) && !FINAL_FAILURE_REGEX.containsMatchIn(message)

private val TRANSIENT_FAILURE_RETRY =
    RetryConfig(
        maxAttempts = 3,
        initialDelay = 1.seconds,
        maxDelay = 4.seconds,
        retryablePatterns = listOf(RetryablePattern.Custom(::isRepeatableFailure)),
        retryAfterExtractor = null,
    )

/**
 * The same client, making a call up to three times when the provider's failure is a passing one.
 *
 * A model call changes nothing on the provider's side until it answers, so a repeat is safe wherever
 * in a turn it happens. Only what survives the repeats reaches the fallback provider or the person.
 */
internal fun LLMClient.repeatingTransientFailures(): LLMClient = RetryingLLMClient(this, TRANSIENT_FAILURE_RETRY)
