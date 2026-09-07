package com.helltar.vusan.tools

import ai.koog.agents.core.tools.ToolException
import kotlin.test.assertFailsWith

/**
 * Runs a tool call that is expected to fail and returns the message the model is shown for it.
 *
 * `suspendToolGuard` reports a failure by throwing, so that koog records the call as failed; the text
 * the exception carries is the same text the model reads as the tool's result.
 */
internal suspend fun toolFailure(block: suspend () -> Unit): String =
    assertFailsWith<ToolException.ValidationFailure> { block() }.message
