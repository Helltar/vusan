package com.helltar.vusan.agent

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentFactoryTest {

    private val meta = ResponseMetaInfo.create(KoogClock.System)

    private fun assistant(vararg parts: MessagePart.ResponsePart) =
        Message.Assistant(parts = parts.toList(), metaInfo = meta)

    private fun user(text: String) =
        Message.User(parts = listOf(MessagePart.Text(text)), metaInfo = RequestMetaInfo.create(KoogClock.System))

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

    @Test
    fun `trailing empty assistant is dropped before the nudge re-request`() {
        val turn = user("ok then")
        assertEquals(listOf(turn), listOf(turn, assistant()).withoutTrailingEmptyAssistant())
    }

    @Test
    fun `assistant with text is kept`() {
        val messages = listOf(user("ok then"), assistant(MessagePart.Text("ok")))
        assertEquals(messages, messages.withoutTrailingEmptyAssistant())
    }

    @Test
    fun `assistant with blank text is kept - it still serializes to string content`() {
        val messages = listOf(user("ok then"), assistant(MessagePart.Text("")))
        assertEquals(messages, messages.withoutTrailingEmptyAssistant())
    }

    @Test
    fun `assistant with a tool call is kept`() {
        val call = MessagePart.Tool.Call(id = "1", tool = "setReaction", args = """{"emoji":"😉"}""")
        val messages = listOf(user("ok then"), assistant(call))
        assertEquals(messages, messages.withoutTrailingEmptyAssistant())
    }

    @Test
    fun `only the trailing empty assistant is dropped`() {
        val earlier = listOf(user("hello"), assistant(MessagePart.Text("earlier reply")), user("ok then"))
        assertEquals(earlier, (earlier + assistant()).withoutTrailingEmptyAssistant())
    }
}
