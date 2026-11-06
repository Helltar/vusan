package com.helltar.vusan.telegram.tools.chat

import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import com.helltar.vusan.request.ChatCapabilities
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.request.requestContext
import com.helltar.vusan.telegram.ChatProfiles
import com.helltar.vusan.tools.toolFailure
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.meta.TelegramUrl
import org.telegram.telegrambots.meta.api.methods.groupadministration.SetChatDescription
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
    fun `the attached picture reaches telegram as the photo`() = runBlocking {
        val bot = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val uploads = mutableListOf<String>()

        // the real client builds the multipart body, so the test sees what telegram would
        bot.createContext("/") { exchange ->
            val method = exchange.requestURI.path.substringAfterLast('/')
            val body = exchange.requestBody.readAllBytes().decodeToString()
            if (method == "setChatPhoto") uploads += body

            val answer =
                when (method) {
                    "getChatMember" -> """{"ok":true,"result":{"status":"creator","is_anonymous":false,"user":{"id":7,"is_bot":false,"first_name":"Ann"}}}"""
                    else -> """{"ok":true,"result":true}"""
                }.toByteArray()

            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, answer.size.toLong())
            exchange.responseBody.use { it.write(answer) }
        }

        bot.start()

        try {
            val client = OkHttpTelegramClient("1:token", TelegramUrl("http", "127.0.0.1", bot.address.port, false))
            val picture = AttachedFile("pride.jpg", 9, "image/jpeg", AttachedFileKind.IMAGE) { "jpeg-bytes".toByteArray() }

            ChatSettingsTools(client, ChatProfiles(client, botId = 1), groupContext(listOf(picture))).setChatPhoto()
        } finally {
            bot.stop(0)
        }

        val upload = uploads.single()
        assertTrue(upload.contains("""name="photo""""), upload)
        assertTrue(upload.contains("jpeg-bytes"), upload)
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
