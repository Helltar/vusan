package com.helltar.vusan.agent

import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.executor.clients.LLMClientException
import com.helltar.vusan.i18n.EnglishMessages
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.i18n.Messages
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ProviderErrorReplyTest {

    private val now = Instant.ofEpochSecond(1_000_000)

    // the shape a spent ChatGPT subscription comes back in: a 429 whose body, not its status, says
    // this is a limit to wait out rather than a rate limit to retry.
    private val usageLimitBody =
        """
        error.message=The usage limit has been reached
        error.plan_type=plus
        error.resets_at=1000900
        error.resets_in_seconds=600
        error.type=usage_limit_reached
        """.trimIndent()

    @Test
    fun `the streaming path's exception counts as a provider error`() {
        val failure =
            IllegalStateException("agent node failed", KoogHttpClientException("OpenAILLMClient", 429, usageLimitBody))

        val message = failure.providerErrorMessage()

        assertTrue(message != null && "usage_limit_reached" in message, message.orEmpty())
    }

    @Test
    fun `a plain failure carries no provider error`() {
        assertNull(IllegalStateException("no route to host").providerErrorMessage())
    }

    @Test
    fun `a spent subscription is answered with the wait it reported`() {
        val reply = EnglishMessages.providerErrorReply(usageLimitBody, now)

        assertEquals("I've hit my usage limit — it resets in about 10min, try again then ⏳", reply)
    }

    @Test
    fun `a spent subscription without a reset time stays vague`() {
        val reply = EnglishMessages.providerErrorReply("Status code: 429\ninsufficient_quota", now)

        assertEquals(EnglishMessages.subscriptionLimitReply(null), reply)
        assertTrue("try again later" in reply, reply)
    }

    @Test
    fun `every language answers a spent subscription both ways`() {
        Language.entries.forEach { language ->
            val messages = Messages.of(language)

            assertTrue(messages.subscriptionLimitReply(null).isNotBlank(), "$language")
            assertTrue(messages.subscriptionLimitReply(90.minutes).isNotBlank(), "$language")
        }
    }

    @Test
    fun `an ordinary rate limit asks for a retry instead`() {
        val reply = EnglishMessages.providerErrorReply("Status code: 429\nrate_limit_exceeded", now)

        assertEquals(EnglishMessages.overloadedReply, reply)
    }

    @Test
    fun `an expired key asks for a new sign-in`() {
        val message = LLMClientException("OpenAILLMClient", "Status code: 401\ntoken_expired").message.orEmpty()

        assertEquals(EnglishMessages.signInRequiredReply, EnglishMessages.providerErrorReply(message, now))
    }

    @Test
    fun `an unrecognized provider error falls back`() {
        assertEquals(
            EnglishMessages.fallbackErrorReply,
            EnglishMessages.providerErrorReply("Status code: 500\nserver_error", now)
        )
    }

    @Test
    fun `the reset countdown wins over the deadline`() {
        assertEquals(600.seconds, usageLimitResetIn(usageLimitBody, now))
    }

    @Test
    fun `a json body spells the reset the same way`() {
        val body = """{"error":{"type":"usage_limit_reached","resets_in_seconds":2910}}"""

        assertEquals(2910.seconds, usageLimitResetIn(body, now))
    }

    @Test
    fun `an epoch deadline is read as the remaining wait`() {
        assertEquals(15.minutes, usageLimitResetIn("error.resets_at=1000900", now))
    }

    @Test
    fun `a deadline already in the past is no wait at all`() {
        assertNull(usageLimitResetIn("error.resets_at=999000", now))
    }

    // a millisecond deadline read as seconds would promise a wait of decades, so it is dropped.
    @Test
    fun `an implausible deadline is ignored`() {
        assertNull(usageLimitResetIn("error.resets_at=1000600000", now))
    }

    // the backend spells a missing value `<null>` rather than omitting the key
    @Test
    fun `a reset the backend left empty yields no wait`() {
        val body = "error.resets_at=<null>\nerror.resets_in_seconds=<null>\nerror.type=usage_limit_reached"

        assertNull(usageLimitResetIn(body, now))
        assertEquals(EnglishMessages.subscriptionLimitReply(null), EnglishMessages.providerErrorReply(body, now))
    }

    @Test
    fun `a body that says nothing about the reset yields no wait`() {
        assertNull(usageLimitResetIn("Status code: 429\nusage_limit_reached", now))
    }
}
