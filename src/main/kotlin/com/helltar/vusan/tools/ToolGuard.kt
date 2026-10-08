package com.helltar.vusan.tools

import com.helltar.vusan.common.rethrowIfCancellation
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger("ToolGuard")

/**
 * A tool that did not do what it was asked, with the message the model reads as the call's result.
 *
 * Thrown rather than returned so the agent records the call as failed: text returned normally is a
 * success, and the run's events and the stored history would then say `ok` for a tool that did not work,
 * and a recap built from that history would read the failure as an accomplished step.
 */
class ToolFailure(message: String) : RuntimeException(message)

/** Turns whatever a tool throws into the [ToolFailure] the agent reports for that call. */
suspend fun suspendToolGuard(block: suspend () -> String): String =
    try {
        block()
    } catch (t: Throwable) {
        t.rethrowIfCancellation()

        if (t is IllegalArgumentException) {
            log.warn { "tool rejected input: ${t.message}" }
        } else {
            log.warn(t) { "tool failed" }
        }

        throw ToolFailure("Tool failed: ${t.message ?: t::class.simpleName}")
    }

fun String.requireToolText(label: String, maxChars: Int): String {
    val trimmed = trim()
    require(trimmed.isNotEmpty()) { "$label must not be empty" }
    require(trimmed.length <= maxChars) { "$label must be at most $maxChars characters" }

    return trimmed
}
