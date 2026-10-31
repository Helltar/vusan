package com.helltar.vusan.agent

import com.helltar.vusan.llm.codex.CodexAuthException
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.llm.LlmException
import com.helltar.vusan.llm.ProviderOutage
import java.time.Instant
import java.time.Duration as JavaDuration
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

// what a provider failure means, read from its status and kept body. providers explain refusals in prose that
// varies by endpoint and vendor, so most rules match the body, and each earns its own reply: a wait, a
// sign-in, a reworded request, or the fallback.

// 429 (rate limit) and 503 or 529 (overloaded) are transient: the provider asks us to back off.
private val TRANSIENT_STATUSES = setOf(429, 503, 529)

// the client already retried these, so by now every attempt failed
private val SERVER_ERROR_STATUSES = setOf(500, 502, 504)

private val CONTEXT_OVERFLOW_REGEX =
    Regex(
        "context[_ ]length|context window|maximum context|too many (input )?tokens|" +
                "prompt is too long|input is too long",
        RegexOption.IGNORE_CASE,
    )

// a spent allowance (Codex usage, xAI credits) is reported in the body, not the status: a plain 429 is a rate
// limit retrying fixes, these mean "come back later". xAI sends a 402 on an API key and a 403 on a Grok
// subscription; openrouter a 403 naming the reset for a key's spending limit or budget.
private val SUBSCRIPTION_LIMIT_REGEX =
    Regex(
        "usage_limit_reached|usage limit reached|usage_not_included|quota_exceeded|" +
                "insufficient_quota|exceeded your current quota|spending-limit|run out of credits|" +
                "key limit exceeded|budget limit exceeded",
        RegexOption.IGNORE_CASE,
    )

// a ChatGPT plan that may no longer use the model (lapsed, or the model retired) gets a 400 naming it, and so
// does every call until the plan or the model changes.
private val MODEL_NOT_IN_PLAN_REGEX =
    Regex("not supported when using codex with a chatgpt account", RegexOption.IGNORE_CASE)

// 402 is a spent allowance on any provider, whatever the body says
private const val PAYMENT_REQUIRED = 402

private val UNAUTHORIZED_REGEX =
    Regex("missing_authorization_header|token_expired|invalid_api_key|authentication_error|unauthorized", RegexOption.IGNORE_CASE)

// a content-policy refusal of the request itself, reported in the body under any status (a flagged stream
// even comes back 200). nothing to wait out or retry; only a reworded request gets through.
private val CONTENT_POLICY_REGEX =
    Regex(
        "cyber_policy|content[_ ]policy|content[_ ]filter|moderation|invalid_prompt|" +
                "prohibited_content|safety system|safety filter|was flagged",
        RegexOption.IGNORE_CASE,
    )

/** The provider failure anywhere in [this]'s cause chain. */
internal fun Throwable.providerError(): LlmException? =
    generateSequence(this) { it.cause }.filterIsInstance<LlmException>().firstOrNull()

private val LlmException.text: String
    get() = body.orEmpty()

private val LlmException.allowanceSpent: Boolean
    get() = status == PAYMENT_REQUIRED || SUBSCRIPTION_LIMIT_REGEX.containsMatchIn(text)

// a 403 alone says nothing about the sign-in: an unserved region, a model the key may not use, a cloudflare
// challenge on the codex backend
private val LlmException.unauthorized: Boolean
    get() = status == 401 || UNAUTHORIZED_REGEX.containsMatchIn(text)

/** Which canned reply a provider error earns, from its status and body. */
internal fun Messages.providerErrorReply(error: LlmException, now: Instant = Instant.now()): String =
    when {
        CONTENT_POLICY_REGEX.containsMatchIn(error.text) -> contentPolicyReply
        error.allowanceSpent -> subscriptionLimitReply(usageLimitResetIn(error.text, now))
        error.unauthorized -> signInRequiredReply
        error.status in TRANSIENT_STATUSES -> overloadedReply
        else -> fallbackErrorReply
    }

// a spent subscription says when it lifts, as a countdown or a deadline (epoch seconds, or an ISO instant on
// openrouter), in JSON or flattened `key=value` lines, so both spellings match.
private val RESETS_IN_REGEX = Regex(""""?resets_in_seconds"?\s*[:=]\s*"?(\d+)""")
private val RESETS_AT_INSTANT_REGEX = Regex(""""?resets_at"?\s*[:=]\s*"?(\d{4}-\d{2}-\d{2}T[\d:.]+Z)""")
private val RESETS_AT_REGEX = Regex(""""?resets_at"?\s*[:=]\s*"?(\d+)""")

// no real window is longer, so a larger value is malformed (milliseconds read as seconds, say) and the reply
// says "later" instead of quoting a fake wait.
private val MAX_USAGE_LIMIT_RESET = 7.days

/** How long until the spent subscription lifts, or `null` when the body does not say. */
internal fun usageLimitResetIn(providerError: String, now: Instant = Instant.now()): Duration? =
    (RESETS_IN_REGEX.longIn(providerError)?.seconds
        ?: RESETS_AT_INSTANT_REGEX.instantIn(providerError)?.let { JavaDuration.between(now, it).toKotlinDuration() }
        ?: RESETS_AT_REGEX.longIn(providerError)?.let { (it - now.epochSecond).seconds })
        ?.takeIf { it.isPositive() && it <= MAX_USAGE_LIMIT_RESET }

private fun Regex.longIn(text: String): Long? =
    find(text)?.groupValues?.get(1)?.toLongOrNull()

private fun Regex.instantIn(text: String): Instant? =
    find(text)?.groupValues?.get(1)?.let { runCatching { Instant.parse(it) }.getOrNull() }

internal fun Throwable.isContextOverflow(): Boolean =
    providerError()?.text?.let(CONTEXT_OVERFLOW_REGEX::containsMatchIn) == true

/**
 * How long the provider behind [this] is out, or `null` when the failure is not the provider's.
 *
 * A spent allowance, a dead sign-in or a model outside the plan refuse every call until something outside the
 * bot changes, so they keep the provider out until the deadline the body names, or [DEFAULT_PROVIDER_OUTAGE].
 * A rate limit, an overload, a server error that outlasted its retries or a call that got no answer usually
 * pass within minutes, so they keep it out for [TRANSIENT_OUTAGE]: long enough that a blip does not flip
 * calls back and forth, short enough not to pay for the fallback after it. A content refusal is no outage: it
 * repeats on any provider.
 */
internal fun Throwable.providerOutage(now: Instant = Instant.now()): ProviderOutage? {
    if (generateSequence(this) { it.cause }.any { it is CodexAuthException }) return now.outageFor(DEFAULT_PROVIDER_OUTAGE)

    val error = providerError() ?: return null

    return when {
        CONTENT_POLICY_REGEX.containsMatchIn(error.text) -> null

        error.allowanceSpent ->
            usageLimitResetIn(error.text, now)?.let { now.outageFor(it, deadlineNamed = true) }
                ?: now.outageFor(DEFAULT_PROVIDER_OUTAGE)

        error.unauthorized || MODEL_NOT_IN_PLAN_REGEX.containsMatchIn(error.text) -> now.outageFor(DEFAULT_PROVIDER_OUTAGE)
        error.status in TRANSIENT_STATUSES || error.status in SERVER_ERROR_STATUSES -> now.outageFor(TRANSIENT_OUTAGE)
        // no answer to read a status from: a timeout, a dropped connection, an unresolvable name
        error.status == null -> now.outageFor(TRANSIENT_OUTAGE)
        else -> null
    }
}

private fun Instant.outageFor(duration: Duration, deadlineNamed: Boolean = false): ProviderOutage =
    ProviderOutage(plus(duration.toJavaDuration()), deadlineNamed)

private val DEFAULT_PROVIDER_OUTAGE = 30.minutes
private val TRANSIENT_OUTAGE = 2.minutes
