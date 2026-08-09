package com.helltar.vusan.telegram

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramBotRunnerTest {

    private val group = -100L
    private val user = 1L
    private val stranger = 2L

    @Test
    fun `an allowlisted chat admits its members and a message without a sender`() {
        assertTrue(isIdAllowed(group, stranger, allowedIds = setOf(group), bannedIds = emptySet()))
        assertTrue(isIdAllowed(group, null, allowedIds = setOf(group), bannedIds = emptySet()))
    }

    @Test
    fun `an allowlisted user is admitted in a chat that is not allowlisted`() {
        assertTrue(isIdAllowed(group, user, allowedIds = setOf(user), bannedIds = emptySet()))
        assertFalse(isIdAllowed(group, stranger, allowedIds = setOf(user), bannedIds = emptySet()))
    }

    @Test
    fun `an empty allowlist admits nobody`() {
        assertFalse(isIdAllowed(group, user, allowedIds = emptySet(), bannedIds = emptySet()))
    }

    @Test
    fun `a banned user stays banned inside an allowlisted chat`() {
        assertFalse(isIdAllowed(group, user, allowedIds = setOf(group), bannedIds = setOf(user)))
        assertTrue(isIdAllowed(group, stranger, allowedIds = setOf(group), bannedIds = setOf(user)))
    }

    @Test
    fun `the ban list wins over the allowlist for the same id`() {
        assertFalse(isIdAllowed(user, user, allowedIds = setOf(user), bannedIds = setOf(user)))
        assertFalse(isIdAllowed(group, user, allowedIds = setOf(group, user), bannedIds = setOf(group)))
    }

    @Test
    fun `a banned chat bans every message in it, sender or not`() {
        assertTrue(isIdBanned(group, user, bannedIds = setOf(group)))
        assertTrue(isIdBanned(group, null, bannedIds = setOf(group)))
        assertFalse(isIdBanned(group, user, bannedIds = emptySet()))
    }
}
