package com.helltar.vusan.tools

import ai.koog.agents.core.tools.ToolException
import com.helltar.vusan.common.rethrowIfCancellation
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger("ToolGuard")

/**
 * Turns whatever a tool throws into the failure koog reports for that call.
 *
 * The message reaches the model as the tool's result either way — that is the point of the guard, the
 * model has to read what went wrong and try something else. Rethrowing as [ToolException] rather than
 * returning the text is what also makes koog record the call as failed: text returned normally is a
 * success to it, so the run's events and the stored history both said `ok` for a tool that did not work,
 * and a recap built from that history read the failure as an accomplished step.
 */
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

        throw ToolException.ValidationFailure("Tool failed: ${t.message ?: t::class.simpleName}")
    }

fun String.requireToolText(label: String, maxChars: Int): String {
    val trimmed = trim()
    require(trimmed.isNotEmpty()) { "$label must not be empty" }
    require(trimmed.length <= maxChars) { "$label must be at most $maxChars characters" }
    return trimmed
}
