package com.helltar.vusan.agent

import com.helltar.vusan.agent.conversation.ChatRole
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.OutboxItem
import com.helltar.vusan.tools.message.MessageTools
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentHistoryPersistenceTest {

    @Test
    fun `rich messages are stored as assistant text`() {
        val outputs = listOf(OutboxItem(BotOutput.RichMessage("# title\n\nbody"), toPrivate = false))

        assertEquals("# title\n\nbody", assistantTextForHistory(outputs, comment = null))
    }

    @Test
    fun `delivery tool pair is skipped when its content is in assistant history`() {
        val event = toolEvent(toolName = MessageTools::sendRichMessage.name, id = "rich")

        val turns = buildTurns("request", listOf(event), "# answer")

        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), turns.map { it.role })
        assertEquals(listOf("request", "# answer"), turns.map { it.content })
    }

    @Test
    fun `tool heavy turns keep only the newest bounded complete pairs`() {
        val events = (1..10).map { toolEvent(toolName = "search", id = "call-$it") }

        val turns = buildTurns("request", events, "answer")

        assertEquals(8, turns.count { it.role == ChatRole.TOOL_CALL })
        assertEquals(8, turns.count { it.role == ChatRole.TOOL_RESULT })
        assertEquals("call-3", turns.first { it.role == ChatRole.TOOL_CALL }.toolCallId)
        assertEquals("call-10", turns.last { it.role == ChatRole.TOOL_RESULT }.toolCallId)
    }

    private fun toolEvent(toolName: String, id: String): ToolEvent =
        ToolEvent(
            toolCallId = id,
            toolName = toolName,
            args = """{"query":"test"}""",
            output = "result",
            isError = false
        )
}
