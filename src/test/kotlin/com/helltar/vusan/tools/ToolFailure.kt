package com.helltar.vusan.tools

import kotlin.test.assertFailsWith

/**
 * Runs a tool call expected to fail and returns the message the model is shown. `suspendToolGuard` reports
 * failure by throwing, so the agent records the call as failed; the exception's text is what the model reads.
 */
internal suspend fun toolFailure(block: suspend () -> Unit): String =
    assertFailsWith<ToolFailure> { block() }.message.orEmpty()
