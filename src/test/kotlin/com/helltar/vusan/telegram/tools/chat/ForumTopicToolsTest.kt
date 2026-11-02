package com.helltar.vusan.telegram.tools.chat

import com.helltar.vusan.delivery.Destination
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.tools.toolFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.meta.api.methods.forum.CloseForumTopic
import org.telegram.telegrambots.meta.api.methods.forum.CreateForumTopic
import org.telegram.telegrambots.meta.api.methods.forum.EditForumTopic
import org.telegram.telegrambots.meta.api.objects.ChatPermissions
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator

class ForumTopicToolsTest {

    private val delivery = RecordingDelivery()

    @Test
    fun `a new topic gets its first message in it, not where the turn is`() = runBlocking {
        val chat = FakeChat()

        val result = tools(chat).createTopic("Trip photos", message = "Post the best ones here.", color = "Green")

        val created = chat.changes.filterIsInstance<CreateForumTopic>().single()
        assertEquals("Trip photos", created.name)
        assertEquals(0x8EEE98, created.iconColor)
        assertEquals(
            listOf(Destination(groupContext().chatRef, FakeChat.NEW_TOPIC_ID.toString()) to listOf<BotOutput>(BotOutput.Text("Post the best ones here."))),
            delivery.unprompted,
        )
        assertTrue("${FakeChat.NEW_TOPIC_ID}" in result, result)
    }

    @Test
    fun `a topic without a message posts nothing and lets telegram pick the color`() = runBlocking {
        val chat = FakeChat()

        tools(chat).createTopic("Trip photos")

        assertNull(chat.changes.filterIsInstance<CreateForumTopic>().single().iconColor)
        assertEquals(emptyList(), delivery.unprompted)
    }

    @Test
    fun `a member of a forum that keeps topics to admins opens none`() = runBlocking {
        val chat = FakeChat(defaults = ChatPermissions.builder().canManageTopics(false).build())

        toolFailure { tools(chat).createTopic("Trip photos", message = "Post the best ones here.") }

        assertEquals(emptyList(), chat.changes)
        assertEquals(emptyList(), delivery.unprompted)
    }

    @Test
    fun `an unknown color is refused before anything is created`() = runBlocking {
        val chat = FakeChat()

        val failure = toolFailure { tools(chat).createTopic("Trip photos", color = "teal") }

        assertTrue("`blue`" in failure, failure)
        assertEquals(emptyList(), chat.changes)
    }

    @Test
    fun `a member who may open topics may not rename one`() = runBlocking {
        val chat = FakeChat()

        val failure = toolFailure { tools(chat, inTopic("12")).renameTopic("Trip photos 2025") }

        assertTrue("rename, close or reopen" in failure, failure)
        assertEquals(emptyList(), chat.changes)
    }

    @Test
    fun `rename acts on the topic the conversation is in`() = runBlocking {
        val chat = topicAdminChat()

        tools(chat, inTopic("12")).renameTopic("Trip photos 2025")

        val edit = chat.changes.filterIsInstance<EditForumTopic>().single()
        assertEquals(12, edit.messageThreadId)
        assertEquals("Trip photos 2025", edit.name)
    }

    @Test
    fun `a named topic wins over the conversation's own`() = runBlocking {
        val chat = topicAdminChat()

        tools(chat, inTopic("12")).closeTopic(topicId = 55)

        assertEquals(listOf(55), chat.changes.filterIsInstance<CloseForumTopic>().map { it.messageThreadId })
    }

    @Test
    fun `the general topic is not closed by accident`() = runBlocking {
        val chat = topicAdminChat()

        toolFailure { tools(chat).closeTopic() }

        assertEquals(emptyList(), chat.changes)
    }

    private fun topicAdminChat() = FakeChat(member = ChatMemberAdministrator.builder().user(person).canManageTopics(true).build())

    private fun inTopic(threadId: String): RequestContext = groupContext().let { it.copy(chat = it.chat.copy(threadId = threadId)) }

    private fun tools(chat: FakeChat, context: RequestContext = groupContext()) = ForumTopicTools(chat.client, delivery, context)
}
