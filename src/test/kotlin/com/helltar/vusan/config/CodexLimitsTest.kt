package com.helltar.vusan.config

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodexLimitsTest {

    private val headers =
        mapOf(
            "x-codex-primary-used-percent" to "6.0",
            "x-codex-primary-window-minutes" to "300",
            "x-codex-primary-reset-at" to "1790620650",
            "x-codex-secondary-used-percent" to "27",
            "x-codex-secondary-window-minutes" to "10080",
        )

    @Test
    fun `both windows are read off the headers the CLI reads`() {
        val windows = codexWindows(headers::get)

        assertEquals(CodexWindow(6.0, 300, Instant.ofEpochSecond(1790620650)), windows?.primary)
        assertEquals(CodexWindow(27.0, 10080, null), windows?.secondary)
    }

    @Test
    fun `a response without the headers states no windows`() {
        assertNull(codexWindows(mapOf("x-codex-primary-window-minutes" to "300")::get))
    }

    @Test
    fun `a window missing its share is left out rather than read as zero`() {
        val windows = codexWindows(mapOf("x-codex-secondary-used-percent" to "garbage", "x-codex-primary-used-percent" to "1")::get)

        assertEquals(1.0, windows?.primary?.usedPercent)
        assertNull(windows?.secondary)
    }

    @Test
    fun `the latest windows are the last response's`() {
        val limits = CodexLimits()
        assertNull(limits.latest)

        limits.observe(headers::get)
        limits.observe((headers + ("x-codex-secondary-used-percent" to "28"))::get)

        assertEquals(28.0, limits.latest?.secondary?.usedPercent)
        assertEquals(6.0, limits.latest?.primary?.usedPercent)
    }
}
