package com.helltar.vusan.telegram

import io.github.oshai.kotlinlogging.KotlinLogging
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton

/**
 * The icons of the bot's own interface: the sign that the live status is still working, and the icons of the
 * stop button and the task menu's buttons. Each is a custom emoji from the public pack
 * [`vusan_ui_by_VusanAgent`](https://t.me/addemoji/vusan_ui_by_VusanAgent), drawn in the reader's text color,
 * with a plain emoji to fall back on. Every deployment uses the same pack, so a custom emoji in it is never
 * deleted; a redrawn one is added beside it under a new id.
 *
 * A bot may send custom emoji only while its owner has Telegram Premium, and nothing says so up front. Without
 * it, telegram keeps the plain emoji in a text and drops the custom one from the message's entities, so the
 * first status that carries the working sign tells ([learnFrom]). A button's icon is dropped without a trace in
 * the answer and the button is left bare, so buttons keep a plain emoji in their text until a status has shown
 * that custom emoji go through.
 */
internal class InterfaceIcons {

    enum class Icon(val customEmojiId: String, val fallback: String) {
        STOP("5438505993577213069", "⏹"),
        PAUSE("5438286619532639132", "⏸"),
        RESUME("5440413002006309207", "▶️"),
        DELETE("5440671271274718851", "🗑"),
        REFRESH("5440560431053715320", "🔄"),
        BACK("5440735588409980265", "↩️"),
    }

    private enum class Support { UNKNOWN, AVAILABLE, UNAVAILABLE }

    @Volatile
    private var support = Support.UNKNOWN

    /**
     * What follows the running line in an HTML status: an animated spinner while custom emoji may work, the
     * plain ellipsis once they do not. A status that drops its markup takes [PLAIN_WORKING] instead.
     */
    val working: String
        get() = if (support == Support.UNAVAILABLE) PLAIN_WORKING else " <tg-emoji emoji-id=\"$WORKING_ID\">$WORKING_FALLBACK</tg-emoji>"

    /** Settles whether custom emoji go through, from [sent], a message whose text carried [working]. */
    fun learnFrom(sent: Message) {
        if (support != Support.UNKNOWN) return

        val kept = sent.entities.orEmpty().any { it.type == CUSTOM_EMOJI_ENTITY }

        support = if (kept) Support.AVAILABLE else Support.UNAVAILABLE
        log.info { "Interface icons: ${if (kept) "custom emoji" else "plain emoji, since telegram dropped the custom one"}" }
    }

    /** A callback button with [icon] before [text]: the custom emoji once it is known to show, the plain one until then. */
    fun button(icon: Icon, text: String, callbackData: String): InlineKeyboardButton {
        val button = InlineKeyboardButton.builder().callbackData(callbackData)

        return if (support == Support.AVAILABLE)
            button.text(text).iconCustomEmojiId(icon.customEmojiId).build()
        else
            button.text("${icon.fallback} $text").build()
    }

    companion object {

        /** The working sign of a status without markup, and of a bot that cannot send custom emoji. */
        const val PLAIN_WORKING = "…"

        private const val WORKING_ID = "5438550592517615894"
        private const val WORKING_FALLBACK = "⏳"
        private const val CUSTOM_EMOJI_ENTITY = "custom_emoji"

        private val log = KotlinLogging.logger {}
    }
}
