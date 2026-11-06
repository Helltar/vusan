package com.helltar.vusan.telegram.tools.chat

import com.helltar.vusan.agent.TurnShelf
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.ChatCapabilities
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat
import org.telegram.telegrambots.meta.api.objects.ChatPhoto
import org.telegram.telegrambots.meta.api.objects.File
import org.telegram.telegrambots.meta.api.objects.chat.ChatFullInfo
import org.telegram.telegrambots.meta.generics.TelegramClient

class ChatPhotoToolsTest {

    @Test
    fun `the group's big photo is sent`() = runBlocking {
        val group = FakeGroupPhoto()
        val outbox = BotOutbox()

        ChatPhotoTools(group.client, groupContext(), outbox).getChatPhoto()

        val photo = assertIs<BotOutput.Photo>(outbox.pending.single().output)
        assertTrue(PHOTO_BYTES.contentEquals(photo.bytes))
        assertEquals(listOf(BIG_FILE_ID), group.fetched)
    }

    @Test
    fun `a photo kept for later is not sent`() = runBlocking {
        val group = FakeGroupPhoto()
        val outbox = BotOutbox()

        val kept = withContext(TurnShelf().open()) { ChatPhotoTools(group.client, groupContext(), outbox).getChatPhoto(send = false) }

        assertContains(kept, "`#1/1`")
        assertTrue(outbox.pending.isEmpty())
    }

    @Test
    fun `a group without a photo says so`() = runBlocking {
        val group = FakeGroupPhoto(photo = null)
        val outbox = BotOutbox()

        val reply = ChatPhotoTools(group.client, groupContext(), outbox).getChatPhoto()

        assertContains(reply, "no photo")
        assertTrue(outbox.pending.isEmpty())
        assertEquals(emptyList(), group.fetched)
    }

    // fetching for a chat that refuses photos would be dropped at the send, so it is answered first
    @Test
    fun `a chat that refuses photos is answered before the fetch`() = runBlocking {
        val group = FakeGroupPhoto()
        val outbox = BotOutbox(ChatCapabilities(photos = false))

        val reply = ChatPhotoTools(group.client, groupContext(), outbox).getChatPhoto()

        assertContains(reply, "does not accept photos")
        assertEquals(emptyList(), group.fetched)
    }

    private class FakeGroupPhoto(
        private val photo: ChatPhoto? = ChatPhoto.builder().smallFileId("small").smallFileUniqueId("s").bigFileId(BIG_FILE_ID).bigFileUniqueId("b").build(),
    ) {

        val fetched = mutableListOf<String>()

        val client: TelegramClient =
            Proxy.newProxyInstance(TelegramClient::class.java.classLoader, arrayOf(TelegramClient::class.java)) { _, method, args ->
                when (method.name) {
                    "executeAsync" ->
                        when (val request = args.single()) {
                            is GetChat ->
                                CompletableFuture.completedFuture(
                                    ChatFullInfo.builder().id(-100L).type("supergroup").accentColorId(0).photo(photo).build(),
                                )

                            is GetFile -> {
                                fetched += request.fileId
                                CompletableFuture.completedFuture(File(request.fileId, "u", null, "photos/file_4.jpg"))
                            }

                            else -> error("unexpected request: $request")
                        }

                    "downloadFileAsStream" -> ByteArrayInputStream(PHOTO_BYTES)
                    else -> error("unexpected client call: ${method.name}")
                }
            } as TelegramClient
    }

    private companion object {
        const val BIG_FILE_ID = "big"
        val PHOTO_BYTES = byteArrayOf(1, 2, 3)
    }
}
