package com.helltar.vusan.telegram

import dev.inmo.tgbotapi.bot.exceptions.CommonRequestException
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
}
