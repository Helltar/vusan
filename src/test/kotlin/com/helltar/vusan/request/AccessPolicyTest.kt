package com.helltar.vusan.request

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessPolicyTest {

    private val group = testChat(-100)
    private val user = testUser(1)
    private val stranger = testUser(2)

    @Test
    fun `an allowlisted chat admits its members and a message without a sender`() {
        val policy = AccessPolicy(allowed = setOf(group.key))

        assertTrue(policy.allows(group, stranger))
        assertTrue(policy.allows(group, null))
    }

    @Test
    fun `an allowlisted user is admitted in a chat that is not allowlisted`() {
        val policy = AccessPolicy(allowed = setOf(user.key))

        assertTrue(policy.allows(group, user))
        assertFalse(policy.allows(group, stranger))
    }

    @Test
    fun `an empty allowlist admits nobody`() {
        assertFalse(AccessPolicy().allows(group, user))
    }

    @Test
    fun `a banned user stays banned inside an allowlisted chat`() {
        val policy = AccessPolicy(allowed = setOf(group.key), banned = setOf(user.key))

        assertFalse(policy.allows(group, user))
        assertTrue(policy.allows(group, stranger))
    }

    @Test
    fun `the ban list wins over the allowlist for the same id`() {
        val ownChat = testChat(1)

        assertFalse(AccessPolicy(allowed = setOf(user.key), banned = setOf(user.key)).allows(ownChat, user))
        assertFalse(
            AccessPolicy(allowed = setOf(group.key, user.key), banned = setOf(group.key)).allows(group, user)
        )
    }

    @Test
    fun `a banned chat bans every message in it, sender or not`() {
        val policy = AccessPolicy(banned = setOf(group.key))

        assertTrue(policy.bans(group, user))
        assertTrue(policy.bans(group, null))
        assertFalse(AccessPolicy().bans(group, user))
    }

    // both platforms issue plain numbers, so an unqualified list would ban or admit a stranger on the
    // other one by coincidence.
    @Test
    fun `a list entry only reaches the platform it names`() {
        val allowed = AccessPolicy(allowed = setOf(testUser(1, Platform.TELEGRAM).key))

        assertTrue(allowed.allows(group, testUser(1, Platform.TELEGRAM)))
        assertFalse(allowed.allows(testChat(-100, Platform.DISCORD), testUser(1, Platform.DISCORD)))

        val banned = AccessPolicy(banned = setOf(testUser(1, Platform.DISCORD).key))

        assertTrue(banned.bans(testChat(-100, Platform.DISCORD), testUser(1, Platform.DISCORD)))
        assertFalse(banned.bans(group, testUser(1, Platform.TELEGRAM)))
    }
}
