package com.helltar.vusan.agent

import com.helltar.vusan.config.CodexAuthException
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.llm.LlmException
import com.helltar.vusan.llm.ProviderOutage
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

// what a provider failure means, read out of the status and the body the client kept. a provider says why
// it refused in prose that varies by endpoint and by vendor, so most rules here are a pattern over the
// body rather than a status code, and each earns a different canned reply: a wait, a sign-in, a
// differently worded request, or nothing but the fallback.

// 429 (rate limit) and 503 or 529 (an overloaded server) are transient — the provider asks us to back off.
private val TRANSIENT_STATUSES = setOf(429, 503, 529)

private val CONTEXT_OVERFLOW_REGEX =
    Regex(
        "context[_ ]length|context window|maximum context|too many (input )?tokens|" +
                "prompt is too long|input is too long",
        RegexOption.IGNORE_CASE,
    )

// a subscription that has run out of Codex usage reports it in the error body rather than by status:
// a plain 429 is an ordinary rate limit that retrying fixes, while these mean "come back later".
private val SUBSCRIPTION_LIMIT_REGEX =
    Regex(
        "usage_limit_reached|usage limit reached|usage_not_included|quota_exceeded|" +
                "insufficient_quota|exceeded your current quota",
        RegexOption.IGNORE_CASE,
    )

private val UNAUTHORIZED_REGEX =
    Regex("missing_authorization_header|token_expired|invalid_api_key|authentication_error|unauthorized", RegexOption.IGNORE_CASE)

// the provider refused the request itself over its content policy, and reports it in the error body:
// the status is whatever the endpoint felt like, a flagged streaming call even comes back as 200. there
// is nothing to wait out and nothing to retry — only a differently worded request gets through.
private val CONTENT_POLICY_REGEX =
    Regex(
        "cyber_policy|content[_ ]policy|content[_ ]filter|moderation|invalid_prompt|" +
                "prohibited_content|safety system|safety filter|was flagged",
        RegexOption.IGNORE_CASE,
    )

/** The provider failure inside [this], wherever in the cause chain the client raised it. */
internal fun Throwable.providerError(): LlmException? =
    generateSequence(this) { it.cause }.filterIsInstance<LlmException>().firstOrNull()

private val LlmException.text: String
    get() = body.orEmpty()

private val LlmException.unauthorized: Boolean
    get() = status == 401 || status == 403 || UNAUTHORIZED_REGEX.containsMatchIn(text)

/** Which canned reply a provider error earns, from its status and its body. */
internal fun Messages.providerErrorReply(error: LlmException, now: Instant = Instant.now()): String =
    when {
        CONTENT_POLICY_REGEX.containsMatchIn(error.text) -> contentPolicyReply
        SUBSCRIPTION_LIMIT_REGEX.containsMatchIn(error.text) -> subscriptionLimitReply(usageLimitResetIn(error.text, now))
        error.unauthorized -> signInRequiredReply
        error.status in TRANSIENT_STATUSES -> overloadedReply
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
    providerError()?.text?.let(CONTEXT_OVERFLOW_REGEX::containsMatchIn) == true

/**
 * How long the provider behind [this] is out for, or `null` when the failure is not the provider's.
 *
 * A spent allowance and a dead sign-in mean every call is refused until something outside the bot
 * changes, so they keep the provider out for the deadline the body names, or [DEFAULT_PROVIDER_OUTAGE]
 * without one. A rate limit, an overloaded server or a call that never got an answer usually passes in
 * seconds, so those keep it out for [TRANSIENT_OUTAGE] only — long enough that a blip does not flip every
 * call back and forth, short enough that the fallback is not paid for after the blip is over. A content
 * refusal is not an outage at all: it repeats on any provider.
 */
internal fun Throwable.providerOutage(now: Instant = Instant.now()): ProviderOutage? {
    if (generateSequence(this) { it.cause }.any { it is CodexAuthException }) return now.outageFor(DEFAULT_PROVIDER_OUTAGE)

    val error = providerError() ?: return null

    return when {
        CONTENT_POLICY_REGEX.containsMatchIn(error.text) -> null

        SUBSCRIPTION_LIMIT_REGEX.containsMatchIn(error.text) ->
            usageLimitResetIn(error.text, now)?.let { now.outageFor(it, deadlineNamed = true) }
                ?: now.outageFor(DEFAULT_PROVIDER_OUTAGE)

        error.unauthorized -> now.outageFor(DEFAULT_PROVIDER_OUTAGE)
        error.status in TRANSIENT_STATUSES -> now.outageFor(TRANSIENT_OUTAGE)
        // the request never got an answer to read a status out of: a timeout, a dropped connection, a name
        // that does not resolve
        error.status == null -> now.outageFor(TRANSIENT_OUTAGE)
        else -> null
    }
}

private fun Instant.outageFor(duration: Duration, deadlineNamed: Boolean = false): ProviderOutage =
    ProviderOutage(plus(duration.toJavaDuration()), deadlineNamed)

private val DEFAULT_PROVIDER_OUTAGE = 30.minutes
private val TRANSIENT_OUTAGE = 2.minutes
