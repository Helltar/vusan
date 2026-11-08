package com.helltar.vusan.telegram

import com.helltar.vusan.agent.ToolActivity
import com.helltar.vusan.telegram.InterfaceIcons.Icon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.telegram.telegrambots.meta.api.objects.MessageEntity
import org.telegram.telegrambots.meta.api.objects.message.Message

class InterfaceIconsTest {

    private fun sent(vararg types: String): Message =
        Message().apply {
            messageId = 1
            entities = types.map { MessageEntity.builder().type(it).offset(0).length(1).build() }
        }

    @Test
    fun `until a status tells, the running line tries the custom sign and buttons keep plain emoji`() {
        val icons = InterfaceIcons()

        assertTrue(InterfaceIcons.carriesCustomEmoji(icons.running(ToolActivity.SEARCHING_WEB, "Searching", html = true)))

        val button = icons.button(Icon.STOP, "Stop", "stop:1")
        assertEquals("⏹\uFE0F Stop", button.text)
        assertNull(button.iconCustomEmojiId)
    }

    @Test
    fun `a status that kept its custom emoji puts the icons on the buttons`() {
        val icons = InterfaceIcons()

        icons.learnFrom(sent("custom_emoji"))

        val button = icons.button(Icon.PAUSE, "Pause #3", "task:pause")
        assertEquals("Pause #3", button.text)
        assertEquals(Icon.PAUSE.customEmojiId, button.iconCustomEmojiId)
        assertTrue(InterfaceIcons.carriesCustomEmoji(icons.done("Searched", html = true)))
    }

    // telegram keeps a bot without the right to custom emoji to the plain one and drops the entity
    @Test
    fun `a status whose custom emoji was dropped falls back to plain emoji everywhere`() {
        val icons = InterfaceIcons()

        icons.learnFrom(sent())

        assertEquals("🌐 Searching…", icons.running(ToolActivity.SEARCHING_WEB, "Searching", html = true))
        assertEquals("✓ Searched", icons.done("Searched", html = true))
        assertEquals("🗑\uFE0F Cancel #3", icons.button(Icon.DELETE, "Cancel #3", "task:cancel").text)
    }

    @Test
    fun `the first answer settles it`() {
        val icons = InterfaceIcons()

        icons.learnFrom(sent())
        icons.learnFrom(sent("custom_emoji"))

        assertEquals("✓ Searched", icons.done("Searched", html = true))
    }
}
