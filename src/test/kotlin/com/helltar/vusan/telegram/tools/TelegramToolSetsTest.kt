package com.helltar.vusan.telegram.tools

import com.helltar.vusan.delivery.OutputDelivery
import com.helltar.vusan.llm.FakeLlmClient
import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.llm.TEST_MODEL
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.request.ChatCapabilities
import com.helltar.vusan.request.requestContext
import com.helltar.vusan.telegram.ChatProfiles
import com.helltar.vusan.telegram.tools.chat.ChatPhotoTools
import com.helltar.vusan.telegram.tools.chat.ChatSettingsTools
import com.helltar.vusan.telegram.tools.chat.ForumTopicTools
import com.helltar.vusan.telegram.tools.sticker.StickerCatalog
import com.helltar.vusan.telegram.tools.sticker.StickerTools
import com.helltar.vusan.tools.PlatformToolSet
import com.helltar.vusan.tools.ToolGroup
import com.helltar.vusan.tools.vision.ImageVisionClient
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import org.telegram.telegrambots.meta.generics.TelegramClient

/**
 * The registry no longer knows telegram's own tools, so the sticker catalog's gate must hold here, and so must
 * the admin rights behind the settings and topic tools. The file tool is offered whatever the chat accepts:
 * `send=false` fetches for another tool, and the tool refuses a send the chat would drop.
 */
class TelegramToolSetsTest {

    private val client: TelegramClient =
        Proxy.newProxyInstance(
            TelegramClient::class.java.classLoader,
            arrayOf(TelegramClient::class.java),
        ) { _, method, _ -> error("the tool sets must not call Telegram to be built: ${method.name}") } as TelegramClient

    private val delivery: OutputDelivery =
        Proxy.newProxyInstance(
            OutputDelivery::class.java.classLoader,
            arrayOf(OutputDelivery::class.java),
        ) { _, method, _ -> error("the tool sets must not deliver anything to be built: ${method.name}") } as OutputDelivery

    private val profiles = ChatProfiles(client, botId = 1)

    private val catalog = StickerCatalog(client, ImageVisionClient(FakeLlmClient(), TEST_MODEL, RequestOptions()))

    @Test
    fun `an unrestricted chat gets both of telegram's own tool sets`() {
        assertEquals(
            listOf(ChatFileTools::class, StickerTools::class),
            toolSetsFor(ChatCapabilities.UNRESTRICTED).map { it.set::class },
        )
    }

    @Test
    fun `a chat that forbids documents still gets the file tool, which refuses at the send`() {
        assertEquals(
            listOf(ChatFileTools::class, StickerTools::class),
            toolSetsFor(ChatCapabilities(documents = false)).map { it.set::class },
        )
    }

    @Test
    fun `a chat that forbids stickers is not offered the catalog`() {
        assertEquals(
            listOf(ChatFileTools::class),
            toolSetsFor(ChatCapabilities(stickersAndAnimations = false)).map { it.set::class },
        )
    }

    // without a vision model nothing described a sticker, so there is no catalog to search.
    @Test
    fun `a deployment without a catalog offers no sticker tools`() {
        val sets = TelegramToolSets(client, delivery, profiles).of(requestContext(), BotOutbox())

        assertEquals(listOf(ChatFileTools::class), sets.map { it.set::class })
    }

    @Test
    fun `a chat that forbids both keeps only the file tool`() {
        assertEquals(
            listOf(ChatFileTools::class),
            toolSetsFor(ChatCapabilities(documents = false, stickersAndAnimations = false)).map { it.set::class },
        )
    }

    // the admin tools are not shown until asked for: a rename is never something a turn needs unprompted
    @Test
    fun `an admin with both rights gets the settings and topic tools behind their groups`() {
        val sets = toolSetsFor(ChatCapabilities(chatInfo = true, topics = true)).filter { it.group != null }

        assertEquals(
            listOf(ChatSettingsTools::class to ToolGroup.CHAT_SETTINGS, ForumTopicTools::class to ToolGroup.FORUM_TOPICS),
            sets.map { it.set::class to it.group },
        )
    }

    @Test
    fun `an admin who may only change the info is not offered topics`() {
        assertEquals(
            listOf(ChatFileTools::class, StickerTools::class, ChatSettingsTools::class),
            toolSetsFor(ChatCapabilities(chatInfo = true)).map { it.set::class },
        )
    }

    // any member sees a group's photo, so reading it needs no admin right, and a private chat has none of its own
    @Test
    fun `a group gets its photo behind a group, a private chat does not`() {
        val group = TelegramToolSets(client, delivery, profiles).of(requestContext(isPrivate = false), BotOutbox())
        val private = TelegramToolSets(client, delivery, profiles).of(requestContext(), BotOutbox())

        assertEquals(ToolGroup.CHAT_PHOTO, group.single { it.set is ChatPhotoTools }.group)
        assertEquals(emptyList(), private.filter { it.set is ChatPhotoTools })
    }

    private fun toolSetsFor(capabilities: ChatCapabilities): List<PlatformToolSet> =
        TelegramToolSets(client, delivery, profiles, catalog).of(requestContext(capabilities = capabilities), BotOutbox())
}
