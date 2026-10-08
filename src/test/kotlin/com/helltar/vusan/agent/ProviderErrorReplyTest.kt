package com.helltar.vusan.agent

import com.helltar.vusan.i18n.EnglishMessages
import com.helltar.vusan.i18n.Language
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.llm.LlmException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    private fun error(status: Int?, body: String) = LlmException("OpenAI", status, body)

    @Test
    fun `a provider error is found wherever it sits in the cause chain`() {
        val failure = IllegalStateException("turn failed", error(429, usageLimitBody))

        assertEquals(429, failure.providerError()?.status)
        assertTrue("usage_limit_reached" in assertNotNull(failure.providerError()).message.orEmpty())
    }

    @Test
    fun `a plain failure carries no provider error`() {
        assertNull(IllegalStateException("no route to host").providerError())
    }

    @Test
    fun `a spent subscription is answered with the wait it reported`() {
        val reply = EnglishMessages.providerErrorReply(error(429, usageLimitBody), now)

        assertEquals("Usage limit reached, resets in about 10min, try again then", reply)
    }

    @Test
    fun `a spent subscription without a reset time stays vague`() {
        val reply = EnglishMessages.providerErrorReply(error(429, "insufficient_quota"), now)

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
    fun `an ordinary rate limit and an overloaded server ask for a retry instead`() {
        assertEquals(EnglishMessages.overloadedReply, EnglishMessages.providerErrorReply(error(429, "rate_limit_exceeded"), now))
        assertEquals(EnglishMessages.overloadedReply, EnglishMessages.providerErrorReply(error(529, """{"type":"overloaded_error"}"""), now))
    }

    @Test
    fun `an expired key asks for a new sign-in`() {
        assertEquals(EnglishMessages.signInRequiredReply, EnglishMessages.providerErrorReply(error(401, "token_expired"), now))
        assertEquals(EnglishMessages.signInRequiredReply, EnglishMessages.providerErrorReply(error(401, ""), now))
    }

    // a region the provider does not serve or a model the key may not use is not a dead sign-in
    @Test
    fun `a forbidden request is not taken for a dead sign-in`() {
        val forbidden = error(403, """{"error":{"type":"permission_error"}}""")

        assertEquals(EnglishMessages.fallbackErrorReply, EnglishMessages.providerErrorReply(forbidden, now))
        assertNull(forbidden.providerOutage(now))
    }

    // a refused request is not an outage: the streaming endpoint answers 200 and puts the refusal in
    // the body, so nothing but the code in it says the turn is over.
    @Test
    fun `a request the provider refused on policy says so`() {
        val body =
            """{"type":"error","error":{"type":"invalid_request","code":"cyber_policy",""" +
                    """"message":"This request was flagged as a security risk. Try rephrasing it."}}"""

        assertEquals(EnglishMessages.contentPolicyReply, EnglishMessages.providerErrorReply(error(200, body), now))
    }

    @Test
    fun `a moderation refusal on any provider reads the same`() {
        assertEquals(EnglishMessages.contentPolicyReply, EnglishMessages.providerErrorReply(error(400, "content_policy_violation"), now))
    }

    @Test
    fun `every language answers a policy refusal`() {
        Language.entries.forEach { language ->
            assertTrue(Messages.of(language).contentPolicyReply.isNotBlank(), "$language")
        }
    }

    @Test
    fun `an unrecognized provider error falls back`() {
        assertEquals(EnglishMessages.fallbackErrorReply, EnglishMessages.providerErrorReply(error(500, "server_error"), now))
    }

    @Test
    fun `a context overflow is recognized by what the provider says`() {
        assertTrue(error(400, "This model's maximum context length is 128000 tokens").isContextOverflow())
        assertTrue(error(400, """{"error":{"message":"prompt is too long: 1050000 tokens"}}""").isContextOverflow())
        assertTrue(!error(400, "unknown parameter").isContextOverflow())
    }

    @Test
    fun `an outage is read off the status and the body`() {
        assertNull(error(400, "content_policy_violation").providerOutage(now), "a refusal repeats on any provider")
        assertNull(error(400, "unknown parameter").providerOutage(now), "a bad request is the bot's own")

        val spent = assertNotNull(error(429, usageLimitBody).providerOutage(now))
        assertEquals(now.plusSeconds(600), spent.until)
        assertTrue(spent.deadlineNamed)

        val rateLimited = assertNotNull(error(429, "rate_limit_exceeded").providerOutage(now))
        assertEquals(now.plusSeconds(120), rateLimited.until)
        assertTrue(!rateLimited.deadlineNamed)

        val unanswered = assertNotNull(error(null, "connection reset").providerOutage(now))
        assertEquals(now.plusSeconds(120), unanswered.until)

        // the client has already made it again by now, so every attempt failed
        for (status in listOf(500, 502, 504)) {
            assertEquals(now.plusSeconds(120), assertNotNull(error(status, "server_error").providerOutage(now)).until)
        }

        val signedOut = assertNotNull(error(401, "token_expired").providerOutage(now))
        assertEquals(now.plusSeconds(1800), signedOut.until)
    }

    @Test
    fun `the reset countdown wins over the deadline`() {
        assertEquals(600.seconds, usageLimitResetIn(usageLimitBody, now))
    }

    @Test
    fun `a json body spells the reset the same way`() {
        val body = """{"error":{"type":"usage_limit_reached","resets_in_seconds":7200,"resets_at":1000900}}"""

        assertEquals(7200.seconds, usageLimitResetIn(body, now))
    }

    @Test
    fun `an epoch deadline is read as the remaining wait`() {
        assertEquals(900.seconds, usageLimitResetIn("error.resets_at=1000900", now))
    }

    @Test
    fun `a deadline already in the past is no wait at all`() {
        assertNull(usageLimitResetIn("error.resets_at=999000", now))
    }

    @Test
    fun `an implausible deadline is ignored`() {
        // milliseconds misread as seconds would promise a wait of decades
        assertNull(usageLimitResetIn("error.resets_in_seconds=1700000000", now))
    }

    @Test
    fun `a reset the backend left empty yields no wait`() {
        val body = "error.resets_at=<null>\nerror.resets_in_seconds=<null>\nerror.type=usage_limit_reached"

        assertNull(usageLimitResetIn(body, now))
        assertEquals(EnglishMessages.subscriptionLimitReply(null), EnglishMessages.providerErrorReply(error(429, body), now))
    }

    @Test
    fun `a body that says nothing about the reset yields no wait`() {
        assertNull(usageLimitResetIn("usage_limit_reached", now))
    }
}
