package com.helltar.vusan.tasks

import com.helltar.vusan.i18n.Language
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class TaskSchedulerTest {

    private val task =
        ScheduledTask(
            id = 7L,
            userId = 100L,
            chatId = -200L,
            prompt = "post the Linux news digest",
            title = "news <digest>",
            recurrence = Recurrence.Every(6.hours),
            timezone = ZoneId.of("Europe/Kyiv"),
            nextFireAt = Instant.parse("2026-07-28T08:00:00Z"),
            createdAt = Instant.parse("2026-07-27T08:00:00Z"),
            enabled = true,
            paused = false,
            creatorMessageId = null,
            creatorUsername = null,
            creatorDisplayName = null,
            chatIsPrivate = false,
            language = Language.ENGLISH
        )

    @Test
    fun `the first attempt carries the task and no retry hint`() {
        val prompt = scheduledTaskPrompt(task, attempt = 1)

        assertContains(prompt, """<scheduled_task title="news &lt;digest&gt;" recurrence="every 6h">""")
        assertContains(prompt, "Task: post the Linux news digest")
        assertTrue(prompt.endsWith("</scheduled_task>"))
        assertFalse(prompt.contains("earlier attempt"))
    }

    @Test
    fun `a retry tells the agent the earlier attempt delivered nothing`() {
        val prompt = scheduledTaskPrompt(task, attempt = 2)

        assertContains(prompt, "Task: post the Linux news digest")
        assertContains(prompt, "An earlier attempt at this task ended without delivering anything.")
        assertTrue(prompt.endsWith("</scheduled_task>"))
    }
}
