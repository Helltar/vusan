package com.helltar.vusan.tools

import com.helltar.vusan.common.rethrowIfCancellation
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger("ToolGuard")

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

        "Tool failed: ${t.message ?: t::class.simpleName}"
    }

fun String.requireToolText(label: String, maxChars: Int): String {
    val trimmed = trim()
    require(trimmed.isNotEmpty()) { "$label must not be empty" }
    require(trimmed.length <= maxChars) { "$label must be at most $maxChars characters" }
    return trimmed
}
