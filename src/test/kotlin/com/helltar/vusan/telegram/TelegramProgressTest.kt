package com.helltar.vusan.telegram

import com.helltar.vusan.agent.ToolActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramProgressTest {

    // a few seconds of work was all it took to earn a status bubble with a stop button on it, which is
    // how looking at a photo somebody had just sent came to be answered as a progress UI.
    @Test
    fun `an activity that is part of the exchange waits far longer than a job`() {
        val job = statusGraceFor(ToolActivity.RUNNING_CODE)

        assertEquals(job, statusGraceFor(ToolActivity.SEARCHING_WEB))

        listOf(ToolActivity.LOOKING_AT_IMAGE, ToolActivity.WRITING, ToolActivity.READING_PAGE).forEach { activity ->
            assertTrue(statusGraceFor(activity) > job, "$activity opens a status message as fast as a job")
        }
    }

    // describeImage and describeVideo are one tool family and land on opposite sides: what earns a
    // message is the work the user is waiting through, not which tool happens to be doing it.
    @Test
    fun `the grace follows the work rather than the tool`() {
        assertTrue(statusGraceFor(ToolActivity.WATCHING_VIDEO) < statusGraceFor(ToolActivity.LOOKING_AT_IMAGE))
    }
}
