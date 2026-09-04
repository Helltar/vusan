package com.helltar.vusan.telegram.delivery

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
    fun `detects a file identifier telegram no longer accepts`() {
        assertTrue(telegramError("Bad Request: wrong remote file identifier specified").isWrongFileIdentifier())
        assertTrue(telegramError("Bad Request: wrong file identifier/HTTP URL specified").isWrongFileIdentifier())
    }

    @Test
    fun `does not read a chat-level sticker refusal as a dead file identifier`() {
        val error = telegramError("Bad Request: not enough rights to send stickers to the chat")

        assertFalse(error.isWrongFileIdentifier())
    }

    @Test
    fun `detects a sticker set telegram no longer has`() {
        assertTrue(telegramError("Bad Request: STICKERSET_INVALID").isStickerSetGone())
        assertTrue(telegramError("Bad Request: sticker set not found").isStickerSetGone())
        assertFalse(telegramError("Bad Gateway").isStickerSetGone())
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
    fun `a content rejection named after forbidden is not a rejected recipient`() {
        // a recipient who allows voice and video messages only from contacts refuses those two kinds
        // and nothing else; reading it as a block would park the chat's tasks and drop the reply.
        val error = telegramError("Bad Request: VOICE_MESSAGES_FORBIDDEN")

        assertFalse(error.isForbidden())
        assertFalse(error.isChatUnreachable())
    }

    @Test
    fun `detects a chat the bot can no longer post into`() {
        val descriptions =
            listOf(
                "Forbidden: bot was kicked from the supergroup chat",
                "Forbidden: bot is not a member of the supergroup chat",
                "Forbidden: bot was blocked by the user",
                "Bad Request: chat not found",
                "Bad Request: CHAT_WRITE_FORBIDDEN",
                "Bad Request: not enough rights to send text messages to the chat",
                "Bad Request: group chat was deactivated"
            )

        descriptions.forEach { description ->
            assertTrue(telegramError(description).isChatUnreachable(), "expected unreachable for [$description]")
        }
    }

    @Test
    fun `a refusal of one output kind is not read as an unreachable chat`() {
        // photos or stickers can be off in a chat that still takes text, and the send fallbacks handle
        // that; treating it as unreachable would park every task scheduled there.
        val descriptions =
            listOf(
                "Bad Request: not enough rights to send photos to the chat",
                "Bad Request: not enough rights to send stickers to the chat",
                "Too Many Requests: retry after 30",
                "Bad Request: message is too long"
            )

        descriptions.forEach { description ->
            assertFalse(telegramError(description).isChatUnreachable(), "expected reachable for [$description]")
        }
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
