package com.helltar.vusan.telegram.tools.chat

import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import com.helltar.vusan.request.ChatCapabilities
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.request.requestContext
import com.helltar.vusan.telegram.ChatProfiles
import com.helltar.vusan.tools.toolFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.meta.api.methods.groupadministration.SetChatDescription
import org.telegram.telegrambots.meta.api.methods.groupadministration.SetChatPhoto
import org.telegram.telegrambots.meta.api.methods.groupadministration.SetChatTitle
import org.telegram.telegrambots.meta.api.objects.ChatPermissions

class ChatSettingsToolsTest {

    @Test
    fun `a member may rename a group that lets members change its info`() = runBlocking {
        val chat = FakeChat()

        tools(chat).setChatTitle("  Weekend hikes  ")

        assertEquals(listOf("Weekend hikes"), chat.changes.filterIsInstance<SetChatTitle>().map { it.title })
    }

    @Test
    fun `a member of a group that keeps its info to admins is refused before anything changes`() = runBlocking {
        val chat = FakeChat(defaults = ChatPermissions.builder().canChangeInfo(false).build())

        val failure = toolFailure { tools(chat).setChatTitle("Weekend hikes") }

        assertTrue("may not change the chat's photo" in failure, failure)
        assertEquals(emptyList(), chat.changes)
    }

    @Test
    fun `a member of a public group is refused even where members may change its info`() = runBlocking {
        val chat = FakeChat(username = "hikers")

        toolFailure { tools(chat).setChatTitle("Weekend hikes") }

        assertEquals(emptyList(), chat.changes)
    }

    @Test
    fun `a message nobody in particular sent changes nothing`() = runBlocking {
        val chat = FakeChat()
        val context = groupContext().let { it.copy(sender = it.sender.copy(isPerson = false)) }

        toolFailure { tools(chat, context).setChatDescription("Trails and meetups.") }

        assertEquals(emptyList(), chat.changes)
    }

    @Test
    fun `the attached picture becomes the photo`() = runBlocking {
        val chat = FakeChat()
        val picture = AttachedFile("avatar.png", 3, "image/png", AttachedFileKind.IMAGE) { byteArrayOf(1, 2, 3) }

        tools(chat, groupContext(listOf(picture))).setChatPhoto()

        assertEquals(listOf("avatar.png"), chat.changes.filterIsInstance<SetChatPhoto>().map { it.photo.mediaName })
    }

    @Test
    fun `a photo needs a picture`() = runBlocking {
        val chat = FakeChat()
        val clip = AttachedFile("clip.mp4", 3, "video/mp4", AttachedFileKind.VIDEO) { byteArrayOf(1, 2, 3) }

        toolFailure { tools(chat, groupContext(listOf(clip))).setChatPhoto() }
        toolFailure { tools(chat).setChatPhoto(clip) }

        assertEquals(emptyList(), chat.changes)
    }

    @Test
    fun `an empty description clears it`() = runBlocking {
        val chat = FakeChat()

        tools(chat).setChatDescription()

        assertEquals(listOf(""), chat.changes.filterIsInstance<SetChatDescription>().map { it.description })
    }

    private fun tools(chat: FakeChat, context: RequestContext = groupContext()) =
        ChatSettingsTools(chat.client, ChatProfiles(chat.client, botId = 1), context)
}

internal fun groupContext(attachedFiles: List<AttachedFile> = emptyList()): RequestContext =
    requestContext(
        chatId = -100L,
        userId = person.id,
        isPrivate = false,
        attachedFiles = attachedFiles,
        capabilities = ChatCapabilities(chatInfo = true, topics = true),
    )
