package com.helltar.vusan.tasks

import com.helltar.vusan.tasks.Recurrence.Cron
import com.helltar.vusan.tasks.Recurrence.Every
import com.helltar.vusan.tasks.Recurrence.Once
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class RecurrenceTest {

    private val kyiv = ZoneId.of("Europe/Kyiv")

    private fun instant(year: Int, month: Int, day: Int, hour: Int, minute: Int, tz: ZoneId = kyiv) =
        LocalDateTime.of(year, month, day, hour, minute).atZone(tz).toInstant()

    @Test
    fun `ONCE never fires again`() {
        assertNull(Once.nextAfter(instant(2026, 5, 24, 9, 0), kyiv))
    }

    @Test
    fun `EVERY adds a fixed interval regardless of timezone`() {
        val before = instant(2026, 5, 24, 9, 0)
        val expected = instant(2026, 5, 24, 9, 30)
        assertEquals(expected, Every(30.minutes).nextAfter(before, kyiv))
    }

    @Test
    fun `EVERY rejects intervals below the minimum`() {
        assertFailsWith<IllegalArgumentException> { Every(1.minutes) }
    }

    @Test
    fun `CRON daily lands on the next matching wall-clock time`() {
        val before = instant(2026, 5, 24, 7, 0)
        val expected = instant(2026, 5, 24, 9, 0)
        assertEquals(expected, Cron("0 9 * * *").nextAfter(before, kyiv))
    }

    @Test
    fun `CRON weekdays skips the weekend`() {
        // 2026-05-23 is a Saturday; next weekday 18:00 is Monday 2026-05-25.
        val saturday = instant(2026, 5, 23, 12, 0)
        val next = Cron("0 18 * * 1-5").nextAfter(saturday, kyiv)!!
        assertEquals(instant(2026, 5, 25, 18, 0), next)
        assertEquals(DayOfWeek.MONDAY, next.atZone(kyiv).dayOfWeek)
    }

    @Test
    fun `CRON rejects malformed expressions`() {
        assertFailsWith<IllegalArgumentException> { Cron("not a cron") }
    }

    @Test
    fun `catchUpAfter skips past fires until result is strictly after now`() {
        val original = instant(2026, 5, 20, 9, 0)
        val now = instant(2026, 5, 24, 12, 0)
        val expected = instant(2026, 5, 25, 9, 0)
        assertEquals(expected, Cron("0 9 * * *").catchUpAfter(original, kyiv, now))
    }

    @Test
    fun `catchUpAfter returns null for ONCE`() {
        val original = instant(2026, 5, 20, 9, 0)
        val now = instant(2026, 5, 24, 12, 0)
        assertNull(Once.catchUpAfter(original, kyiv, now))
    }

    @Test
    fun `catchUpAfter on EVERY lands on next slot strictly after now`() {
        val original = instant(2026, 5, 24, 9, 0)
        val now = instant(2026, 5, 24, 9, 35)
        // slots: 09:30, 10:00 — first strictly after 09:35 is 10:00.
        assertEquals(instant(2026, 5, 24, 10, 0), Every(30.minutes).catchUpAfter(original, kyiv, now))
    }

    @Test
    fun `serialize round-trips through parse`() {
        listOf(Once, Every(45.minutes), Every(3.hours), Cron("0 18 * * 1-5"), Cron("0 0 1,15 * *"))
            .forEach { assertEquals(it, Recurrence.parse(it.serialize())) }
    }

    @Test
    fun `parse rejects unknown tokens and below-minimum intervals`() {
        assertNull(Recurrence.parse("daily"))
        assertNull(Recurrence.parse("every:PT1M"))
        assertNull(Recurrence.parse("cron:not a cron"))
    }

    private val now = instant(2026, 5, 24, 12, 0)

    private fun ok(raw: String) = parseSchedule(raw, now, kyiv) as ScheduleParse.Ok

    @Test
    fun `parseSchedule once yields a one-shot at the given time`() {
        val parsed = ok("once 2026-05-30T09:00")
        assertEquals(Once, parsed.recurrence)
        assertEquals(instant(2026, 5, 30, 9, 0), parsed.firstFire)
    }

    @Test
    fun `parseSchedule every starts one interval from now`() {
        val parsed = ok("every 90m")
        assertEquals(Every(90.minutes), parsed.recurrence)
        assertEquals(now.plusSeconds(90 * 60), parsed.firstFire)
    }

    @Test
    fun `parseSchedule cron lands on the next match`() {
        val parsed = ok("cron 0 18 * * 1-5")
        assertEquals(Cron("0 18 * * 1-5"), parsed.recurrence)
        assertTrue(parsed.firstFire.isAfter(now))
        assertEquals(18, parsed.firstFire.atZone(kyiv).hour)
    }

    @Test
    fun `parseSchedule reports errors for bad input`() {
        assertTrue(parseSchedule("weekly", now, kyiv) is ScheduleParse.Err)
        assertTrue(parseSchedule("once yesterday", now, kyiv) is ScheduleParse.Err)
        assertTrue(parseSchedule("once 2020-01-01T09:00", now, kyiv) is ScheduleParse.Err)
        assertTrue(parseSchedule("every 1m", now, kyiv) is ScheduleParse.Err)
        assertTrue(parseSchedule("cron nonsense", now, kyiv) is ScheduleParse.Err)
    }
}
