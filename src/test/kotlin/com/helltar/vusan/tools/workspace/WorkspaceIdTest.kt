package com.helltar.vusan.tools.workspace

import com.helltar.vusan.request.RequestContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class WorkspaceIdTest {

    private fun context(userId: Long, chatId: Long, private: Boolean) =
        RequestContext(chatId = chatId, userId = userId, messageId = 1L, chatIsPrivate = private)

    @Test
    fun `a private chat is keyed by the person alone`() {
        assertEquals("u4242", workspaceIdOrNull(context(userId = 4242, chatId = 4242, private = true)))
    }

    @Test
    fun `a group is keyed by the person alone`() {
        assertEquals("u4242", workspaceIdOrNull(context(4242, -1001234567890, private = false)))
    }

    @Test
    fun `two people in one group get workspaces of their own`() {
        val first = workspaceIdOrNull(context(1, -1002, private = false))
        val second = workspaceIdOrNull(context(2, -1002, private = false))
        assertNotEquals(first, second)
    }

    @Test
    fun `senders telegram delivers under a shared bot account get no workspace`() {
        assertNull(workspaceIdOrNull(context(1_087_968_824, -1002, private = false)))
        assertNull(workspaceIdOrNull(context(136_817_688, -1002, private = false)))
    }

    @Test
    fun `the same person shares files across private chat and multiple groups`() {
        val inPrivate = workspaceIdOrNull(context(7, 7, private = true))
        val inGroup = workspaceIdOrNull(context(7, -1007, private = false))
        assertEquals(inPrivate, inGroup)
        assertEquals(inPrivate, workspaceIdOrNull(context(7, -2007, private = false)))
    }
}
