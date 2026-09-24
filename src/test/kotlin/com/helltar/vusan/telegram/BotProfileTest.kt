package com.helltar.vusan.telegram

import kotlin.test.Test
import kotlin.test.assertEquals

class BotProfileTest {

    @Test
    fun `the profile name leads, followed by the handle without its bot suffix`() {
        assertEquals(listOf("Robin", "robin_helper"), profile(username = "robin_helperBot").addressingNames(emptyList()))
        assertEquals(listOf("Robin", "robo"), profile(username = "robo_bot").addressingNames(emptyList()))
    }

    @Test
    fun `configured names replace the ones read off the handle`() {
        assertEquals(listOf("Robin", "robbie", "rob"), profile().addressingNames(listOf("robbie", " rob ")))
    }

    @Test
    fun `a name repeated in another case is kept once`() {
        assertEquals(listOf("Robin"), profile(username = "robinbot").addressingNames(emptyList()))
        assertEquals(listOf("Robin", "robbie"), profile().addressingNames(listOf("ROBIN", "robbie", "Robbie")))
    }

    private fun profile(username: String? = "robinbot") =
        BotProfile(userId = 1, username = username, displayName = "Robin")
}
