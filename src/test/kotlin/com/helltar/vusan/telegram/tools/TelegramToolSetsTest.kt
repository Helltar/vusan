package com.helltar.vusan.telegram.tools

import com.helltar.vusan.llm.RequestOptions
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.request.ChatCapabilities
import com.helltar.vusan.request.requestContext
import com.helltar.vusan.telegram.tools.sticker.StickerCatalog
import com.helltar.vusan.telegram.tools.sticker.StickerTools
import com.helltar.vusan.llm.FakeLlmClient
import com.helltar.vusan.tools.vision.ImageVisionClient
import com.helltar.vusan.llm.TEST_MODEL
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.telegram.telegrambots.meta.generics.TelegramClient

/**
 * The registry stopped gating Telegram's own tools when it stopped knowing about them, so the gate has
 * to hold here instead: a chat that refuses a kind of content must not be offered the tool that sends it.
 */
class TelegramToolSetsTest {

    private val client: TelegramClient =
        Proxy.newProxyInstance(
            TelegramClient::class.java.classLoader,
            arrayOf(TelegramClient::class.java)
        ) { _, method, _ -> error("the tool sets must not call Telegram to be built: ${method.name}") } as TelegramClient

    private val catalog = StickerCatalog(client, ImageVisionClient(FakeLlmClient(), TEST_MODEL, RequestOptions()))

    @Test
    fun `an unrestricted chat gets both of telegram's own tool sets`() {
        assertEquals(
            listOf(ChatFileTools::class, StickerTools::class),
            toolSetsFor(ChatCapabilities.UNRESTRICTED).map { it::class },
        )
    }

    @Test
    fun `a chat that forbids documents is not offered resending by file id`() {
        assertEquals(
            listOf(StickerTools::class),
            toolSetsFor(ChatCapabilities(documents = false)).map { it::class },
        )
    }

    @Test
    fun `a chat that forbids stickers is not offered the catalog`() {
        assertEquals(
            listOf(ChatFileTools::class),
            toolSetsFor(ChatCapabilities(stickersAndAnimations = false)).map { it::class },
        )
    }

    // without a vision model nothing ever described a sticker, so there is no catalog to search.
    @Test
    fun `a deployment without a catalog offers no sticker tools`() {
        val sets = TelegramToolSets(client).of(requestContext(), BotOutbox())

        assertEquals(listOf(ChatFileTools::class), sets.map { it::class })
    }

    @Test
    fun `a chat that allows neither gets nothing`() {
        assertTrue(
            toolSetsFor(ChatCapabilities(documents = false, stickersAndAnimations = false)).isEmpty(),
        )
    }

    private fun toolSetsFor(capabilities: ChatCapabilities): List<ToolSet> =
        TelegramToolSets(client, catalog).of(requestContext(capabilities = capabilities), BotOutbox())
}
