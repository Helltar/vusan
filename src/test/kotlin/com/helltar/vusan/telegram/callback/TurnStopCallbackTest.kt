package com.helltar.vusan.telegram.callback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TurnStopCallbackTest {

    // anyone in a group can press the button, so the owner must survive the round trip, or a bystander's tap
    // would end someone else's turn.
    @Test
    fun `the owner survives the round trip`() {
        assertEquals(4321L, turnStopOwnerId(turnStopCallbackData(4321L)))
        assertEquals(-1001L, turnStopOwnerId(turnStopCallbackData(-1001L)))
    }

    @Test
    fun `anything this build did not write has no owner`() {
        assertNull(turnStopOwnerId("stop:"))
        assertNull(turnStopOwnerId("stop:someone"))
        assertNull(turnStopOwnerId("tasks:refresh:7"))
        assertNull(turnStopOwnerId(""))
    }
}
