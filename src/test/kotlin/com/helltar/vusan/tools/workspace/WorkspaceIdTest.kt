package com.helltar.vusan.tools.workspace

import com.helltar.vusan.request.RequestContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WorkspaceIdTest {

    private fun context(userId: Long, chatId: Long, private: Boolean) =
        RequestContext(chatId = chatId, userId = userId, messageId = 1L, chatIsPrivate = private)

    @Test
    fun `a private chat is keyed by the person alone`() {
        assertEquals("u4242", workspaceId(context(userId = 4242, chatId = 4242, private = true)))
    }

    @Test
    fun `a group is keyed by the person alone`() {
        assertEquals("u4242", workspaceId(context(4242, -1001234567890, private = false)))
    }

    @Test
    fun `two people in one group get workspaces of their own`() {
        val first = workspaceId(context(1, -1002, private = false))
        val second = workspaceId(context(2, -1002, private = false))
        assertNotEquals(first, second)
    }

    @Test
    fun `the same person shares files across private chat and multiple groups`() {
        val inPrivate = workspaceId(context(7, 7, private = true))
        val inGroup = workspaceId(context(7, -1007, private = false))
        assertEquals(inPrivate, inGroup)
        assertEquals(inPrivate, workspaceId(context(7, -2007, private = false)))
    }
}
