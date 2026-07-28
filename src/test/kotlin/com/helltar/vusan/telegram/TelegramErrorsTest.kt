package com.helltar.vusan.telegram

import java.io.Serializable
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.telegram.telegrambots.meta.api.objects.ApiResponse
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException

class TelegramErrorsTest {

    @Test
    fun `detects html parse errors from telegram`() {
        val error = telegramError("Bad Request: can't parse entities: Can't find end of the entity starting at byte offset 12")

        assertTrue(error.isEntityParseError())
    }

    @Test
    fun `detects unsupported html tag errors from telegram`() {
        val error = telegramError("Bad Request: can't parse entities: Unsupported start tag \"user_message\" at byte offset 12")

        assertTrue(error.isEntityParseError())
    }

    @Test
    fun `detects api errors wrapped in generic client exceptions`() {
        val error =
            TelegramApiException(
                "Unable to execute sendMessage method",
                telegramError("Bad Request: can't parse entities: Can't find end tag corresponding to start tag \"pre\"")
            )

        assertTrue(error.isEntityParseError())
    }

    @Test
    fun `does not treat unrelated telegram errors as formatting issues`() {
        val error = telegramError("Bad Request: reply message not found")

        assertFalse(error.isEntityParseError())
    }

    @Test
    fun `detects forbidden and chat-not-found delivery rejections`() {
        val descriptions =
            listOf(
                "Forbidden: bot was blocked by the user",
                "Forbidden: bot can't initiate conversation with a user",
                "Forbidden: user is deactivated",
                "Bad Request: chat not found"
            )

        descriptions.forEach { description ->
            assertTrue(telegramError(description).isForbidden(), "expected isForbidden for [$description]")
        }
    }

    @Test
    fun `does not treat unrelated telegram errors as forbidden`() {
        val error = telegramError("Bad Request: message is too long")

        assertFalse(error.isForbidden())
    }

    @Test
    fun `detects the old reply-not-found wording`() {
        val error = telegramError("Bad Request: reply message not found")

        assertTrue(error.isReplyMessageNotFound())
    }

    @Test
    fun `detects the new reply-not-found wording`() {
        val error = telegramError("Bad Request: message to be replied not found")

        assertTrue(error.isReplyMessageNotFound())
    }

    @Test
    fun `does not treat unrelated telegram errors as missing reply`() {
        val error = telegramError("Bad Request: message to edit not found")

        assertFalse(error.isReplyMessageNotFound())
    }

    @Test
    fun `detects an unchanged edited message`() {
        val error =
            telegramError(
                "Bad Request: message is not modified: specified new message content and reply markup " +
                        "are exactly the same as the current content and reply markup"
            )

        assertTrue(error.isMessageNotModified())
    }

    @Test
    fun `does not treat an unavailable message as unchanged`() {
        val error = telegramError("Bad Request: message to edit not found")

        assertFalse(error.isMessageNotModified())
    }

    private fun telegramError(description: String): TelegramApiRequestException =
        TelegramApiRequestException(
            "Error executing request",
            ApiResponse.builder<Serializable>()
                .ok(false)
                .errorCode(400)
                .errorDescription(description)
                .build()
        )
}
