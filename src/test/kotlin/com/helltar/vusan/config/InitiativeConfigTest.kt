package com.helltar.vusan.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InitiativeConfigTest {

    @Test
    fun `quiet hours hold from the first hour up to the last one`() {
        val hours = QuietHours.parse("INITIATIVE_QUIET_HOURS", "1-8")

        assertEquals(QuietHours(from = 1, until = 8), hours)
        assertTrue(1 in hours)
        assertTrue(7 in hours)
        assertFalse(8 in hours)
        assertFalse(0 in hours)
    }

    @Test
    fun `quiet hours may run over midnight`() {
        val hours = QuietHours.parse("INITIATIVE_QUIET_HOURS", " 23 - 7 ")

        assertTrue(23 in hours)
        assertTrue(3 in hours)
        assertFalse(7 in hours)
        assertFalse(12 in hours)
    }

    @Test
    fun `a range that starts where it ends is empty`() {
        val hours = QuietHours.parse("INITIATIVE_QUIET_HOURS", "0-0")

        assertTrue((0..23).none { it in hours })
    }

    @Test
    fun `unreadable quiet hours stop the startup`() {
        assertFailsWith<IllegalStateException> { QuietHours.parse("INITIATIVE_QUIET_HOURS", "night") }
        assertFailsWith<IllegalStateException> { QuietHours.parse("INITIATIVE_QUIET_HOURS", "1-24") }
    }

    @Test
    fun `an interval short enough to join every exchange is refused`() {
        assertFailsWith<IllegalArgumentException> { InitiativeConfig(intervalMinutes = 4) }
    }
}
