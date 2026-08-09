package com.helltar.vusan.telegram

import com.helltar.vusan.request.ChatCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.telegram.telegrambots.meta.api.objects.ChatPermissions
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.api.objects.chat.ChatFullInfo
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberMember
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberRestricted

class ChatProfileTest {

    private val botUser: User = User.builder().id(42L).isBot(true).firstName("Vusan").build()

    @Test
    fun `a chat nobody restricted allows everything`() {
        val capabilities = capabilitiesOf(member(), chat(permissions = allowing()))

        assertEquals(ChatCapabilities.UNRESTRICTED, capabilities)
        assertTrue(capabilities.restrictedKinds.isEmpty())
    }

    @Test
    fun `a plain member is bound by the chat's default permissions`() {
        val capabilities = capabilitiesOf(member(), chat(permissions = allowing(photos = false, polls = false)))

        assertFalse(capabilities.photos)
        assertFalse(capabilities.polls)
        assertTrue(capabilities.videos)
        assertEquals(listOf("photos", "polls"), capabilities.restrictedKinds)
    }

    @Test
    fun `an administrator is bound by neither permissions nor slow mode`() {
        val capabilities =
            capabilitiesOf(
                ChatMemberAdministrator.builder().user(botUser).build(),
                chat(permissions = ChatPermissions.builder().build(), slowModeDelay = 30)
            )

        assertEquals(ChatCapabilities.UNRESTRICTED, capabilities)
        assertEquals(0, capabilities.slowModeSeconds)
    }

    @Test
    fun `a restriction on the bot itself replaces the chat's defaults`() {
        // the chat lets everyone post pictures; this bot alone was silenced on them.
        val member =
            ChatMemberRestricted.builder()
                .user(botUser)
                .isMember(true)
                .canSendMessages(true)
                .canSendPhotos(false)
                .canSendVideos(true)
                .untilDate(0)
                .build()

        val capabilities = capabilitiesOf(member, chat(permissions = allowing()))

        assertFalse(capabilities.photos)
        assertTrue(capabilities.videos)
    }

    @Test
    fun `slow mode is carried through for anyone it applies to`() {
        val capabilities = capabilitiesOf(member(), chat(permissions = allowing(), slowModeDelay = 30))

        assertEquals(30, capabilities.slowModeSeconds)
    }

    @Test
    fun `a lookup that answered nothing leaves the chat unrestricted`() {
        // guessing "forbidden" from a failed call would strip the agent of tools it actually has.
        assertEquals(ChatCapabilities.UNRESTRICTED, capabilitiesOf(membership = null, chat = null))
        assertEquals(ChatCapabilities.UNRESTRICTED, capabilitiesOf(member(), chat(permissions = null)))
    }

    @Test
    fun `an omitted reaction permission follows the messages permission`() {
        val silenced = allowing(messages = false, reactions = null)

        assertFalse(capabilitiesOf(member(), chat(permissions = silenced)).reactions)
        assertTrue(capabilitiesOf(member(), chat(permissions = allowing(reactions = null))).reactions)
    }

    private fun member() = ChatMemberMember.builder().user(botUser).build()

    private fun chat(permissions: ChatPermissions?, slowModeDelay: Int? = null): ChatFullInfo =
        ChatFullInfo.builder()
            .id(-100L)
            .type("supergroup")
            .accentColorId(0)
            .permissions(permissions)
            .slowModeDelay(slowModeDelay)
            .build()

    private fun allowing(
        messages: Boolean = true,
        photos: Boolean = true,
        polls: Boolean = true,
        reactions: Boolean? = true
    ): ChatPermissions =
        ChatPermissions.builder()
            .canSendMessages(messages)
            .canSendPhotos(photos)
            .canSendVideos(true)
            .canSendAudios(true)
            .canSendDocuments(true)
            .canSendVoiceNotes(true)
            .canSendVideoNotes(true)
            .canSendPolls(polls)
            .canSendOtherMessages(true)
            .canReactToMessages(reactions)
            .build()
}
