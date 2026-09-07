package com.helltar.vusan.telegram.callback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TurnStopCallbackTest {

    // the button sits on a message everyone in a group can press, so the owner has to survive the round
    // trip: without it a bystander's tap would end somebody else's turn.
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
