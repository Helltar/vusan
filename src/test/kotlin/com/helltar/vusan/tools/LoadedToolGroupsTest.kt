package com.helltar.vusan.tools

import com.helltar.vusan.request.testScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoadedToolGroupsTest {

    @Test
    fun `a conversation gets back what it loaded`() {
        val groups = LoadedToolGroups()
        val scope = testScope(userId = 1)

        groups.remember(scope, listOf(ToolGroup.IMAGE_GENERATION))

        assertEquals(setOf(ToolGroup.IMAGE_GENERATION), groups.of(scope))
    }

    @Test
    fun `one conversation does not read another's`() {
        val groups = LoadedToolGroups()
        groups.remember(testScope(userId = 1), listOf(ToolGroup.VOICE_REPLIES))

        assertTrue(groups.of(testScope(userId = 2)).isEmpty())
        assertTrue(groups.of(testScope(userId = 1, chatId = -11)).isEmpty())
    }

    // the point is a stable tool array, not a growing one: an old request may not pin the whole menu
    // open for the rest of the day.
    @Test
    fun `only the three most recent groups are kept`() {
        val groups = LoadedToolGroups()
        val scope = testScope(userId = 1)

        groups.remember(scope, listOf(ToolGroup.CURRENCY))
        groups.remember(scope, listOf(ToolGroup.POLLS))
        groups.remember(scope, listOf(ToolGroup.GIFS))
        groups.remember(scope, listOf(ToolGroup.YOUTUBE))

        assertEquals(setOf(ToolGroup.POLLS, ToolGroup.GIFS, ToolGroup.YOUTUBE), groups.of(scope))
    }

    @Test
    fun `loading a group again keeps it from falling out`() {
        val groups = LoadedToolGroups()
        val scope = testScope(userId = 1)

        groups.remember(scope, listOf(ToolGroup.CURRENCY))
        groups.remember(scope, listOf(ToolGroup.POLLS))
        groups.remember(scope, listOf(ToolGroup.GIFS))
        groups.remember(scope, listOf(ToolGroup.CURRENCY))
        groups.remember(scope, listOf(ToolGroup.YOUTUBE))

        assertEquals(setOf(ToolGroup.GIFS, ToolGroup.CURRENCY, ToolGroup.YOUTUBE), groups.of(scope))
    }

    @Test
    fun `remembering nothing changes nothing`() {
        val groups = LoadedToolGroups()
        val scope = testScope(userId = 1)

        groups.remember(scope, emptyList())

        assertTrue(groups.of(scope).isEmpty())
    }
}
