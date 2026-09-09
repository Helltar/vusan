package com.helltar.vusan.tasks

import com.helltar.vusan.i18n.Language
import com.helltar.vusan.telegram.ChatProfile
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
            creatorThreadId = null,
            creatorMessageId = null,
            creatorUsername = null,
            creatorDisplayName = null,
            chatIsPrivate = false,
            language = Language.ENGLISH
        )

    @Test
    fun `a fired task still names who set it up and where`() {
        val block =
            task
                .copy(creatorUsername = "helltar", creatorDisplayName = "Helltar")
                .toMessageContext()
                .toPromptBlock()

        assertContains(block, "- id: -200")
        assertContains(block, "- private: false")
        assertContains(block, "- id: 100")
        assertContains(block, "- username: helltar")
        assertContains(block, "- display_name: Helltar")
    }

    @Test
    fun `a fired task runs in the topic it was created in`() {
        val request = scheduledAgentRequest(task.copy(creatorThreadId = 42), attempt = 1, ChatProfile.NONE)

        // a follow-up the turn schedules is anchored from here, so losing the topic sends it to General.
        assertEquals(42, request.messageThreadId)
        assertEquals(-200L, request.chatId)
        assertEquals(0L, request.messageId)
        assertNull(request.replyToMessageId)
    }

    @Test
    fun `a task outside a topic carries no thread`() {
        assertNull(scheduledAgentRequest(task, attempt = 1, ChatProfile.NONE).messageThreadId)
    }

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
