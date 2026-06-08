package com.helltar.vusan.agent

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentFactoryTest {

    private val meta = ResponseMetaInfo.create(KoogClock.System)

    private fun assistant(vararg parts: MessagePart.ResponsePart) =
        Message.Assistant(parts = parts.toList(), metaInfo = meta)

    @Test
    fun `empty assistant message delivered nothing`() {
        assertTrue(assistant().deliveredNothing())
    }

    @Test
    fun `blank text delivered nothing`() {
        assertTrue(assistant(MessagePart.Text("   \n ")).deliveredNothing())
    }

    @Test
    fun `non-blank text counts as a deliverable caption`() {
        assertFalse(assistant(MessagePart.Text("here you go")).deliveredNothing())
    }

    @Test
    fun `a pending tool call is not nothing`() {
        val call = MessagePart.Tool.Call(id = "1", tool = "sendMessage", args = """{"text":"hi"}""")
        assertFalse(assistant(call).deliveredNothing())
    }

    @Test
    fun `a tool call alongside blank text is not nothing`() {
        val call = MessagePart.Tool.Call(id = "1", tool = "webSearch", args = "{}")
        assertFalse(assistant(MessagePart.Text(""), call).deliveredNothing())
    }
}
