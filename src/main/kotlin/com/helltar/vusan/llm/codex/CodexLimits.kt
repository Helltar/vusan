package com.helltar.vusan.llm.codex

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant

/** One of the subscription's usage windows, as the backend last reported it. */
data class CodexWindow(val usedPercent: Double, val windowMinutes: Long?, val resetAt: Instant?)

/** The short window (five hours on Plus) and the long one (a week), either of which may be missing. */
data class CodexWindows(val primary: CodexWindow?, val secondary: CodexWindow?)

/**
 * The windows the Codex backend states in the headers of every response, the same `x-codex-*` family its
 * CLI reads; `null` when a response carries none.
 */
internal fun codexWindows(header: (String) -> String?): CodexWindows? {
    fun window(name: String): CodexWindow? {
        val used = header("x-codex-$name-used-percent")?.trim()?.toDoubleOrNull() ?: return null

        return CodexWindow(
            usedPercent = used,
            windowMinutes = header("x-codex-$name-window-minutes")?.trim()?.toLongOrNull(),
            resetAt = header("x-codex-$name-reset-at")?.trim()?.toLongOrNull()?.let(Instant::ofEpochSecond),
        )
    }

    val primary = window("primary")
    val secondary = window("secondary")

    return CodexWindows(primary, secondary).takeIf { primary != null || secondary != null }
}

/**
 * How much of the signed-in account's subscription the bot has drawn, measured against the tokens it
 * spent. OpenAI publishes no size for a window, only the share of it used, and that share moves in steps:
 * so every step is logged with the calls and tokens spent since the step before, and those pairs are
 * what says how much a window holds.
 */
class CodexLimits {

    private var windows: CodexWindows? = null
    private var spent = Spent()

    /** The windows as the latest response stated them, or `null` before the first one. */
    val latest: CodexWindows?
        get() = synchronized(this) { windows }

    /** Reads the windows off a response's headers, logging when a share moved. */
    fun observe(header: (String) -> String?) {
        val now = codexWindows(header) ?: return

        val (previous, since) =
            synchronized(this) {
                val previous = windows
                windows = now

                if (previous != null && previous.sameShares(now)) return

                val since = spent
                spent = Spent()
                previous to since
            }

        log.info {
            "codex limits: primary=[${now.primary.label()}] secondary=[${now.secondary.label()}] " +
                    "was=[${previous?.primary.label()}, ${previous?.secondary.label()}] " +
                    "primaryResetAt=[${now.primary?.resetAt ?: "n/a"}] secondaryResetAt=[${now.secondary?.resetAt ?: "n/a"}] " +
                    "spent calls=[${since.calls}] images=[${since.images}] inputTokens=[${since.inputTokens}] " +
                    "cachedInputTokens=[${since.cachedInputTokens}] outputTokens=[${since.outputTokens}]"
        }
    }

    /** Counts one model call's tokens toward the next step. */
    fun countCall(inputTokens: Long, cachedInputTokens: Long, outputTokens: Long) {
        synchronized(this) {
            spent =
                spent.copy(
                    calls = spent.calls + 1,
                    inputTokens = spent.inputTokens + inputTokens,
                    cachedInputTokens = spent.cachedInputTokens + cachedInputTokens,
                    outputTokens = spent.outputTokens + outputTokens,
                )
        }
    }

    /** Counts one picture drawn or edited through the subscription toward the next step. */
    fun countImage() {
        synchronized(this) { spent = spent.copy(images = spent.images + 1) }
    }

    private data class Spent(
        val calls: Long = 0,
        val images: Long = 0,
        val inputTokens: Long = 0,
        val cachedInputTokens: Long = 0,
        val outputTokens: Long = 0,
    )

    private companion object {
        val log = KotlinLogging.logger {}

        fun CodexWindows.sameShares(other: CodexWindows): Boolean =
            primary?.usedPercent == other.primary?.usedPercent && secondary?.usedPercent == other.secondary?.usedPercent

        fun CodexWindow?.label(): String {
            if (this == null) return "n/a"

            val share = if (usedPercent % 1.0 == 0.0) usedPercent.toLong().toString() else usedPercent.toString()

            return "$share%" + windowMinutes?.let { "/${it}min" }.orEmpty()
        }
    }
}
