package com.helltar.vusan.telegram

import dev.inmo.tgbotapi.bot.exceptions.CommonRequestException
import dev.inmo.tgbotapi.bot.exceptions.ReplyMessageNotFoundException
import dev.inmo.tgbotapi.types.Response
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramErrorsTest {

    @Test
    fun `detects markdown parse errors from telegram`() {
        val error =
            CommonRequestException(
                response = Response(description = "Bad Request: can't parse entities: Can't find end of the entity starting at byte offset 12"),
                plainAnswer = "",
                message = null,
                cause = null
            )

        assertTrue(error.isMarkdownError())
    }

    @Test
    fun `detects markdownv2 reserved character errors from telegram`() {
        val error =
            CommonRequestException(
                response = Response(description = "Bad Request: can't parse entities: Character '.' is reserved and must be escaped with the preceding '\\'"),
                plainAnswer = "",
                message = null,
                cause = null
            )

        assertTrue(error.isMarkdownError())
    }

    @Test
    fun `does not treat unrelated telegram errors as markdown issues`() {
        val error =
            CommonRequestException(
                response = Response(description = "Bad Request: reply message not found"),
                plainAnswer = "",
                message = null,
                cause = null
            )

        assertFalse(error.isMarkdownError())
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
            val error = CommonRequestException(Response(description = description), "", null, null)
            assertTrue(error.isForbidden(), "expected isForbidden for [$description]")
        }
    }

    @Test
    fun `does not treat unrelated telegram errors as forbidden`() {
        val error =
            CommonRequestException(
                response = Response(description = "Bad Request: message is too long"),
                plainAnswer = "",
                message = null,
                cause = null
            )

        assertFalse(error.isForbidden())
    }

    @Test
    fun `detects reply-not-found wording unclassified by ktgbotapi`() {
        val error =
            CommonRequestException(
                response = Response(description = "Bad Request: message to be replied not found"),
                plainAnswer = "",
                message = null,
                cause = null
            )

        assertTrue(error.isReplyMessageNotFound())
    }

    @Test
    fun `detects reply-not-found exception classified by ktgbotapi`() {
        val error =
            ReplyMessageNotFoundException(
                response = Response(description = "Bad Request: reply message not found"),
                plainAnswer = "",
                message = null,
                cause = null
            )

        assertTrue(error.isReplyMessageNotFound())
    }

    @Test
    fun `does not treat unrelated telegram errors as missing reply`() {
        val error =
            CommonRequestException(
                response = Response(description = "Bad Request: message to edit not found"),
                plainAnswer = "",
                message = null,
                cause = null
            )

        assertFalse(error.isReplyMessageNotFound())
    }
}
