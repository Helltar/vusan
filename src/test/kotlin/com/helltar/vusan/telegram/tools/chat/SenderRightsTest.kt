package com.helltar.vusan.telegram.tools.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.telegram.telegrambots.meta.api.objects.ChatPermissions
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberLeft
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberMember
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberOwner
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberRestricted

class SenderRightsTest {

    private val member = ChatMemberMember.builder().user(person).build()

    @Test
    fun `a member may ask for what the chat lets every member do`() {
        val defaults = ChatPermissions.builder().canChangeInfo(true).build()

        assertTrue(holds(member, defaults, ChatRight.CHANGE_INFO, isPublic = false))
        assertFalse(holds(member, defaults, ChatRight.CREATE_TOPICS, isPublic = false))
    }

    // telegram ignores a member's `can_change_info` in a public group
    @Test
    fun `a member of a public group may not change its info whatever the defaults say`() {
        assertFalse(holds(member, ChatPermissions.builder().canChangeInfo(true).build(), ChatRight.CHANGE_INFO, isPublic = true))
    }

    // a member's `can_manage_topics` only opens topics; someone else's belongs to the admins
    @Test
    fun `a member who may open topics may not rename or close them`() {
        val defaults = ChatPermissions.builder().canManageTopics(true).build()

        assertTrue(holds(member, defaults, ChatRight.CREATE_TOPICS, isPublic = false))
        assertFalse(holds(member, defaults, ChatRight.MANAGE_TOPICS, isPublic = false))
    }

    @Test
    fun `an omitted topic permission follows the pin permission`() {
        assertTrue(holds(member, ChatPermissions.builder().canPinMessages(true).build(), ChatRight.CREATE_TOPICS, isPublic = false))
    }

    @Test
    fun `a member of a chat that keeps its info to admins may not`() {
        assertFalse(holds(member, ChatPermissions.builder().canChangeInfo(false).build(), ChatRight.CHANGE_INFO, isPublic = false))
        assertFalse(holds(member, defaults = null, ChatRight.CHANGE_INFO, isPublic = false))
    }

    @Test
    fun `an admin holds only the rights given to them`() {
        val admin = ChatMemberAdministrator.builder().user(person).canChangeInfo(false).canManageTopics(true).build()

        assertFalse(holds(admin, defaults = null, ChatRight.CHANGE_INFO, isPublic = false))
        assertTrue(holds(admin, defaults = null, ChatRight.MANAGE_TOPICS, isPublic = false))
    }

    @Test
    fun `the owner holds every right`() {
        assertTrue(holds(ChatMemberOwner.builder().user(person).build(), defaults = null, ChatRight.CHANGE_INFO, isPublic = true))
    }

    @Test
    fun `a restriction replaces the chat's defaults`() {
        val restricted = ChatMemberRestricted.builder().user(person).isMember(true).canChangeInfo(false).build()
        val defaults = ChatPermissions.builder().canChangeInfo(true).build()

        assertFalse(holds(restricted, defaults, ChatRight.CHANGE_INFO, isPublic = false))
    }

    @Test
    fun `someone who left holds nothing`() {
        assertFalse(holds(ChatMemberLeft.builder().user(person).build(), ChatPermissions.builder().canChangeInfo(true).build(), ChatRight.CHANGE_INFO, isPublic = false))
    }
}
