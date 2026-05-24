package com.helltar.vusan.reminders

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecurrenceTest {

    private val kyiv = ZoneId.of("Europe/Kyiv")

    private fun instant(year: Int, month: Int, day: Int, hour: Int, minute: Int, tz: ZoneId = kyiv) =
        LocalDateTime.of(year, month, day, hour, minute).atZone(tz).toInstant()

    @Test
    fun `ONCE never advances`() {
        val now = instant(2026, 5, 24, 9, 0)
        assertNull(Recurrence.ONCE.advance(now, kyiv))
    }

    @Test
    fun `DAILY adds one calendar day in the user's timezone`() {
        val before = instant(2026, 5, 24, 9, 0)
        val expected = instant(2026, 5, 25, 9, 0)
        assertEquals(expected, Recurrence.DAILY.advance(before, kyiv))
    }

    @Test
    fun `WEEKLY preserves day-of-week`() {
        val mondayMorning = instant(2026, 5, 25, 9, 0)
        val nextMonday = instant(2026, 6, 1, 9, 0)
        assertEquals(nextMonday, Recurrence.WEEKLY.advance(mondayMorning, kyiv))
    }

    @Test
    fun `MONTHLY preserves day-of-month`() {
        val firstOfJune = instant(2026, 6, 1, 9, 0)
        val firstOfJuly = instant(2026, 7, 1, 9, 0)
        assertEquals(firstOfJuly, Recurrence.MONTHLY.advance(firstOfJune, kyiv))
    }

    @Test
    fun `MONTHLY clamps to last valid day when target month is shorter`() {
        // Jan 31 → Feb 28 (java.time semantics)
        val jan31 = instant(2027, 1, 31, 9, 0)
        val feb28 = instant(2027, 2, 28, 9, 0)
        assertEquals(feb28, Recurrence.MONTHLY.advance(jan31, kyiv))
    }

    @Test
    fun `catchUpAfter skips past fires until result is strictly after now`() {
        // Daily reminder originally at May 20 09:00; bot back online May 24 12:00.
        // Should advance to May 25 09:00, skipping 21, 22, 23, 24.
        val original = instant(2026, 5, 20, 9, 0)
        val now = instant(2026, 5, 24, 12, 0)
        val expected = instant(2026, 5, 25, 9, 0)
        assertEquals(expected, Recurrence.DAILY.catchUpAfter(original, kyiv, now))
    }

    @Test
    fun `catchUpAfter returns null for ONCE`() {
        val original = instant(2026, 5, 20, 9, 0)
        val now = instant(2026, 5, 24, 12, 0)
        assertNull(Recurrence.ONCE.catchUpAfter(original, kyiv, now))
    }

    @Test
    fun `catchUpAfter on weekly with multi-week gap lands on next correct weekday`() {
        val mondayMorning = instant(2026, 5, 25, 9, 0)
        val now = instant(2026, 6, 20, 12, 0)
        val next = Recurrence.WEEKLY.catchUpAfter(mondayMorning, kyiv, now)!!
        assertTrue(next.isAfter(now))
        val nextZoned = next.atZone(kyiv)
        assertEquals(mondayMorning.atZone(kyiv).dayOfWeek, nextZoned.dayOfWeek)
        assertEquals(9, nextZoned.hour)
    }

    @Test
    fun `parse accepts case-insensitive enum names`() {
        assertEquals(Recurrence.DAILY, Recurrence.parse("daily"))
        assertEquals(Recurrence.WEEKLY, Recurrence.parse("Weekly"))
        assertEquals(Recurrence.ONCE, Recurrence.parse(" ONCE "))
        assertNull(Recurrence.parse("hourly"))
    }
}
