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

    private companion object {
        const val CHAT_ID = -100_1234567890L
    }

    private fun floodError(retryAfter: Int): TelegramApiRequestException =
        TelegramApiRequestException(
            "Error executing request",
            ApiResponse.builder<Serializable>()
                .ok(false)
                .errorCode(429)
                .errorDescription("Too Many Requests: retry after $retryAfter")
                .parameters(ResponseParameters(null, retryAfter))
                .build()
        )
}
