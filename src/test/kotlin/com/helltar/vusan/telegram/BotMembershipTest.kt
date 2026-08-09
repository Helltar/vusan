package com.helltar.vusan.telegram

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberBanned
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberLeft
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberMember
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberRestricted

class BotMembershipTest {

    private val botUser: User = User.builder().id(42L).isBot(true).firstName("Vusan").build()

    @Test
    fun `leaving or being kicked means the bot can no longer post`() {
        assertFalse(ChatMemberLeft.builder().user(botUser).build().allowsPosting())
        assertFalse(ChatMemberBanned.builder().user(botUser).untilDate(0).build().allowsPosting())
    }

    @Test
    fun `an ordinary member or admin can post`() {
        assertTrue(ChatMemberMember.builder().user(botUser).build().allowsPosting())
        assertTrue(ChatMemberAdministrator.builder().user(botUser).build().allowsPosting())
    }

    @Test
    fun `a restriction only counts when it takes messages away`() {
        // a chat that turned photos off still takes text, so tasks scheduled there keep working.
        assertTrue(restricted(canSendMessages = true).allowsPosting())
        assertFalse(restricted(canSendMessages = false).allowsPosting())
        assertFalse(restricted(canSendMessages = null).allowsPosting())
    }

    private fun restricted(canSendMessages: Boolean?): ChatMemberRestricted =
        ChatMemberRestricted.builder()
            .user(botUser)
            .isMember(true)
            .canSendMessages(canSendMessages)
            .untilDate(0)
            .build()
}
