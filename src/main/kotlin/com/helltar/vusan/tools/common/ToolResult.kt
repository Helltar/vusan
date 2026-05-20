package com.helltar.vusan.tools.common

import com.helltar.vusan.common.rethrowIfCancellation
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger("ToolGuard")

suspend fun suspendToolGuard(block: suspend () -> String): String =
    try {
        block()
    } catch (t: Throwable) {
        t.rethrowIfCancellation()
        log.warn(t) { "tool failed" }
        "Tool failed: ${t.message ?: t::class.simpleName}"
    }
