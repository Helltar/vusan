package com.helltar.vusan.agent

import com.helltar.vusan.agent.grouplog.GroupLogEntry
import com.helltar.vusan.request.testChat
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class RecentChatSliceTest {

    @Test
    fun `an ordinary turn leaves out the exchange its history already replays`() {
        val entries = listOf(person("1", ALICE, "anyone seen the keys"), person("2", BOB, "robin where"), bot("on the shelf", "2"))

        val slice = recentChatSlice(entries, senderId = BOB, messageId = "9", ambient = false)

        assertEquals(listOf("anyone seen the keys"), slice.map { it.text })
    }

    @Test
    fun `an ambient turn keeps the bot's lines in the order the chat had them`() {
        val entries =
            listOf(
                person("2", BOB, "and why"),
                bot("because the news came first", "2"),
                person("3", ALICE, "lol"),
                person("4", BOB, "which one"),
            )

        val slice = recentChatSlice(entries, senderId = BOB, messageId = "4", ambient = true)

        assertEquals(listOf("and why", "because the news came first", "lol"), slice.map { it.text })
    }

    @Test
    fun `an ambient slice ends right before the message, whatever came after it`() {
        val entries =
            listOf(bot("because the news came first", "2"), person("4", BOB, "which one"), person("5", ALICE, "later line"))

        val slice = recentChatSlice(entries, senderId = BOB, messageId = "4", ambient = true)

        assertEquals(listOf("because the news came first"), slice.map { it.text })
    }

    @Test
    fun `a message the log has not recorded yet leaves the ambient slice uncut`() {
        val entries = listOf(bot("because the news came first", "2"), person("3", ALICE, "lol"))

        assertEquals(entries, recentChatSlice(entries, senderId = BOB, messageId = "4", ambient = true))
    }

    private fun person(messageId: String, senderId: String, text: String) =
        GroupLogEntry(
            chat = CHAT,
            messageId = messageId,
            kind = "text",
            sentAt = NOON,
            senderId = senderId,
            senderName = senderId,
            text = text,
        )

    private fun bot(text: String, answering: String) =
        GroupLogEntry(
            chat = CHAT,
            messageId = null,
            kind = GroupLogEntry.BOT_KIND,
            sentAt = NOON,
            text = text,
            replyToMessageId = answering,
        )

    private companion object {
        val CHAT = testChat(-100)
        val NOON: Instant = Instant.parse("2026-09-24T12:00:00Z")
        const val ALICE = "7"
        const val BOB = "8"
    }
}
