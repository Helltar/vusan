package com.helltar.vusan.telegram.delivery

import java.io.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.meta.api.objects.ApiResponse
import org.telegram.telegrambots.meta.api.objects.ResponseParameters
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException

class TelegramSendFallbacksTest {

    @Test
    fun `waits out flood control and sends again`() = runBlocking {
        var attempts = 0

        withFloodWaitRetry(CHAT_ID) {
            attempts++
            if (attempts == 1) throw floodError(retryAfter = 1)
        }

        assertEquals(2, attempts)
    }

    @Test
    fun `gives up after one repeat rather than looping on a chat that stays flooded`() = runBlocking {
        var attempts = 0

        assertFailsWith<TelegramApiRequestException> {
            withFloodWaitRetry(CHAT_ID) {
                attempts++
                throw floodError(retryAfter = 1)
            }
        }

        assertEquals(2, attempts)
    }

    @Test
    fun `does not sit through a wait longer than a turn is worth`() = runBlocking {
        var attempts = 0

        assertFailsWith<TelegramApiRequestException> {
            withFloodWaitRetry(CHAT_ID) {
                attempts++
                throw floodError(retryAfter = 600)
            }
        }

        assertEquals(1, attempts)
    }

    @Test
    fun `leaves every other failure to the fallbacks`() = runBlocking {
        var attempts = 0

        assertFailsWith<IllegalStateException> {
            withFloodWaitRetry(CHAT_ID) {
                attempts++
                error("sendPhoto failed")
            }
        }

        assertEquals(1, attempts)
    }

    @Test
    fun `turns a fenced block into a pre with its language and escapes the code`() {
        val text = "Like so:\n```kotlin\nif (a < b && c > d) run()\n```\nDone."

        assertEquals(
            "Like so:\n<pre><code class=\"language-kotlin\">if (a &lt; b &amp;&amp; c &gt; d) run()</code></pre>\nDone.",
            text.withModelMarkupRepaired(),
        )
    }

    @Test
    fun `turns a fence without a language into a bare pre`() {
        assertEquals("<pre>total 4\ndrwx------ 2 me me 4096 Jan 1 .</pre>", "```\ntotal 4\ndrwx------ 2 me me 4096 Jan 1 .\n```".withModelMarkupRepaired())
    }

    @Test
    fun `turns a backticked span into inline code`() {
        assertEquals("run <code>make &lt;target&gt;</code> first", "run `make <target>` first".withModelMarkupRepaired())
    }

    @Test
    fun `keeps an entity the model already wrote inside the code`() {
        assertEquals("<pre>x &lt; y &amp;&amp; z</pre>", "```\nx &lt; y && z\n```".withModelMarkupRepaired())
    }

    @Test
    fun `leaves a backtick inside html code alone`() {
        val text = "<pre>const s = `hi ${'$'}{name}`;</pre> and <code>`</code> but `this`"

        assertEquals("<pre>const s = `hi ${'$'}{name}`;</pre> and <code>`</code> but <code>this</code>", text.withModelMarkupRepaired())
    }

    @Test
    fun `leaves a run of backticks that is not a fence as typed`() {
        assertEquals("``` not a block ```", "``` not a block ```".withModelMarkupRepaired())
    }

    @Test
    fun `still turns br tags into newlines`() {
        assertEquals("one\ntwo\n<code>three</code>", "one<br>two<br/>`three`".withModelMarkupRepaired())
    }

    private fun floodError(retryAfter: Int): TelegramApiRequestException =
        TelegramApiRequestException(
            "Error executing request",
            ApiResponse.builder<Serializable>()
                .ok(false)
                .errorCode(429)
                .errorDescription("Too Many Requests: retry after $retryAfter")
                .parameters(ResponseParameters(null, retryAfter))
                .build(),
        )

    private companion object {
        const val CHAT_ID = -100_1234567890L
    }
}
