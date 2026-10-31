package com.helltar.vusan.telegram

import com.helltar.vusan.agent.ToolActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramProgressTest {

    // a few seconds of work once earned a status bubble with a stop button, so looking at a just-sent photo
    // was answered as a progress UI.
    @Test
    fun `an activity that is part of the exchange waits far longer than a job`() {
        val job = statusGraceFor(ToolActivity.RUNNING_CODE)

        assertEquals(job, statusGraceFor(ToolActivity.SEARCHING_WEB))

        listOf(ToolActivity.LOOKING_AT_IMAGE, ToolActivity.WRITING, ToolActivity.READING_PAGE).forEach { activity ->
            assertTrue(statusGraceFor(activity) > job, "$activity opens a status message as fast as a job")
        }
    }

    // describeImage and describeVideo, one family, land on opposite sides: what earns a message is work the
    // user waits through, not the tool.
    @Test
    fun `the grace follows the work rather than the tool`() {
        assertTrue(statusGraceFor(ToolActivity.WATCHING_VIDEO) < statusGraceFor(ToolActivity.LOOKING_AT_IMAGE))
    }
}
