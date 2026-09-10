package com.helltar.vusan.agent

import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.executor.clients.LLMClientException
import com.helltar.vusan.i18n.Messages
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

// what a provider failure means, read out of the message koog folded the status and the error body
// into. a provider says why it refused in prose that varies by endpoint and by vendor, so every rule
// here is a pattern rather than a status code, and each earns a different canned reply: a wait, a
// sign-in, a differently worded request, or nothing but the fallback.

// the provider's HTTP status is embedded in the client exception message ("Status code: 429").
// 429 (rate limit / quota) and 503 (service overloaded) are transient — the provider asks us to back off.
private val TRANSIENT_STATUS_REGEX = Regex("""Status code:\s*(429|503)""")
private val CONTEXT_OVERFLOW_REGEX =
    Regex(
        "context[_ ]length|context window|maximum context|too many (input )?tokens|" +
                "prompt is too long|input is too long",
        RegexOption.IGNORE_CASE
    )

// a subscription that has run out of Codex usage reports it in the error body rather than by status:
// a plain 429 is an ordinary rate limit that retrying fixes, while these mean "come back later".
private val SUBSCRIPTION_LIMIT_REGEX =
    Regex(
        "usage_limit_reached|usage limit reached|usage_not_included|quota_exceeded|" +
                "insufficient_quota|exceeded your current quota",
        RegexOption.IGNORE_CASE
    )

private val UNAUTHORIZED_REGEX =
    Regex(
        "\\b401\\b|missing_authorization_header|token_expired|invalid_api_key|unauthorized",
        RegexOption.IGNORE_CASE
    )

// the provider refused the request itself over its content policy, and reports it in the error body:
// the status is whatever the endpoint felt like, a flagged streaming call even comes back as 200. there
// is nothing to wait out and nothing to retry — only a differently worded request gets through.
private val CONTENT_POLICY_REGEX =
    Regex(
        "cyber_policy|content[_ ]policy|content[_ ]filter|moderation|invalid_prompt|" +
                "prohibited_content|safety system|safety filter|was flagged",
        RegexOption.IGNORE_CASE
    )

/**
 * The provider failure inside [this], as the message koog built for it.
 *
 * Koog raises one of two types depending on which layer refused the call — the client wrapper, or the
 * raw HTTP/SSE path the Codex bridge streams over — and both fold the status code and the error body
 * into their message.
 */
internal fun Throwable.providerErrorMessage(): String? =
    generateSequence(this) { it.cause }
        .firstOrNull { it is LLMClientException || it is KoogHttpClientException }
        ?.message

/** Which canned reply a provider error earns, from the status and the error body koog embedded in it. */
internal fun Messages.providerErrorReply(providerError: String, now: Instant = Instant.now()): String =
    when {
        CONTENT_POLICY_REGEX.containsMatchIn(providerError) -> contentPolicyReply

        SUBSCRIPTION_LIMIT_REGEX.containsMatchIn(providerError) ->
            subscriptionLimitReply(usageLimitResetIn(providerError, now))

        UNAUTHORIZED_REGEX.containsMatchIn(providerError) -> signInRequiredReply
        TRANSIENT_STATUS_REGEX.containsMatchIn(providerError) -> overloadedReply
        else -> fallbackErrorReply
    }

// an exhausted subscription says when it lifts, as a countdown or as an epoch-second deadline. the body
// is JSON on some endpoints and flattened `key=value` lines on others, so match both spellings.
private val RESETS_IN_REGEX = Regex(""""?resets_in_seconds"?\s*[:=]\s*"?(\d+)""")
private val RESETS_AT_REGEX = Regex(""""?resets_at"?\s*[:=]\s*"?(\d+)""")

// no real subscription window is longer than this, so a larger value is a malformed deadline (seconds
// misread from milliseconds, say) and the reply falls back to "later" rather than quoting a fake wait.
private val MAX_USAGE_LIMIT_RESET = 7.days

/** How long until the exhausted subscription lifts, or `null` when the error body does not say. */
internal fun usageLimitResetIn(providerError: String, now: Instant = Instant.now()): Duration? =
    (RESETS_IN_REGEX.longIn(providerError)?.seconds
        ?: RESETS_AT_REGEX.longIn(providerError)?.let { (it - now.epochSecond).seconds })
        ?.takeIf { it.isPositive() && it <= MAX_USAGE_LIMIT_RESET }

private fun Regex.longIn(text: String): Long? =
    find(text)?.groupValues?.get(1)?.toLongOrNull()

internal fun Throwable.isContextOverflow(): Boolean =
    providerErrorMessage()?.let(CONTEXT_OVERFLOW_REGEX::containsMatchIn) == true
