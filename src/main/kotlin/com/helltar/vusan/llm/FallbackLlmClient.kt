package com.helltar.vusan.llm

import com.helltar.vusan.common.rethrowIfCancellation
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.toKotlinDuration
import java.time.Duration as JavaDuration

/**
 * The fallback [model] answering while the primary is out, and how long until the primary is due back
 * — known only when the primary named that deadline itself.
 */
data class FallbackInUse(val model: String, val primaryBackIn: Duration?)

/**
 * A provider that is out [until] a deadline. [deadlineNamed] is true only when the provider stated that
 * deadline itself; otherwise it is merely when the next probe is due, and no promise to anybody.
 */
class ProviderOutage(val until: Instant, val deadlineNamed: Boolean)

/**
 * The primary provider with a second one behind it for when the first is out: a spent subscription, a
 * sign-in that expired. A call the primary refuses that way is repeated on the fallback with the
 * fallback's own model and options, and the primary is left alone until the deadline its refusal named,
 * after which one call probes it again.
 *
 * It wraps the client rather than the agent so that one place covers every call the bot makes — a
 * turn, a history recap, a group-log digest, vision on the chat model — and so the turn that ran into
 * the limit finishes on the fallback instead of ending in "come back later". [outageOf] reads a failure
 * into the outage it means, or `null` when the failure is not the provider's and reaches the caller.
 */
class FallbackLlmClient(
    private val primary: LlmClient,
    private val primaryLabel: String,
    private val fallback: LlmClient,
    private val fallbackLabel: String,
    private val fallbackModel: LlmModel,
    private val fallbackOptions: RequestOptions,
    private val outageOf: (failure: Throwable, now: Instant) -> ProviderOutage?,
    private val clock: Clock = Clock.systemUTC(),
) : LlmClient {

    @Volatile
    private var primaryOutage: ProviderOutage? = null

    // past the deadline one call tries the primary; the rest stay on the fallback until it has answered
    private val probing = AtomicBoolean(false)

    /** What is answering right now while the primary is out, or `null` when it is the primary's turn. */
    val fallbackInUse: FallbackInUse?
        get() {
            val now = clock.instant()
            val outage = primaryOutage?.takeIf { it.until.isAfter(now) } ?: return null

            // only a deadline the provider named is worth repeating to people: the others are when the
            // next probe is due, and a sign-in that expired stays expired however many of them pass.
            val primaryBackIn =
                outage.takeIf { it.deadlineNamed }?.let { JavaDuration.between(now, it.until).toKotlinDuration() }

            return FallbackInUse(fallbackModel.id, primaryBackIn)
        }

    override suspend fun complete(model: LlmModel, request: ChatRequest): Reply {
        val now = clock.instant()
        val known = primaryOutage

        if (known != null && known.until.isAfter(now)) return onFallback(request)
        if (known != null && !probing.compareAndSet(false, true)) return onFallback(request)

        if (known != null) log.info { "probing $primaryLabel again after its outage" }

        return try {
            primary.complete(model, request).also { if (known != null) recovered() }
        } catch (e: Throwable) {
            e.rethrowIfCancellation()
            val outage = outageOf(e, now) ?: throw e

            primaryOutage = outage

            log.warn {
                "$primaryLabel is out for ${JavaDuration.between(now, outage.until).toMinutes()}m: " +
                        "${e.message?.lineSequence()?.firstOrNull()}; answering from $fallbackLabel until then"
            }

            onFallback(request)
        } finally {
            if (known != null) probing.set(false)
        }
    }

    override fun close() {
        primary.close()
        fallback.close()
    }

    // the fallback speaks with its own options, but what the caller asked of this one call stays: the
    // conversation's cache key, which lets the fallback build a warm prefix per conversation the same way,
    // whether the prompt is worth caching at all, and an output ceiling of the call's own.
    private suspend fun onFallback(request: ChatRequest): Reply {
        val asked = request.options

        val options =
            fallbackOptions.copy(
                promptCacheKey = fallbackOptions.promptCacheKey?.let { asked.promptCacheKey ?: it },
                cachePrompt = asked.cachePrompt,
                maxOutputTokens = asked.maxOutputTokens ?: fallbackOptions.maxOutputTokens,
            )

        return fallback.complete(fallbackModel, request.copy(options = options))
    }

    private fun recovered() {
        primaryOutage = null
        log.info { "$primaryLabel is back; $fallbackLabel stands down" }
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
