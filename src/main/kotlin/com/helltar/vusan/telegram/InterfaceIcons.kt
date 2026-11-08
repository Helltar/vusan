package com.helltar.vusan.telegram

import com.helltar.vusan.agent.ToolActivity
import io.github.oshai.kotlinlogging.KotlinLogging
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton

/**
 * The icons of the bot's own interface: an animation for every activity of the live status, the mark of a step
 * it finished, and the icons of the stop button and the task menu's buttons. Each is a custom emoji from the
 * public pack [`vusan_ui_by_VusanAgent`](https://t.me/addemoji/vusan_ui_by_VusanAgent), drawn in the reader's
 * text color, with a plain emoji to fall back on. Every deployment uses the same pack, so a custom emoji in it is
 * never deleted; a redrawn one is added beside it under a new id.
 *
 * A bot may send custom emoji only while its owner has Telegram Premium, and nothing says so up front. Without
 * it, telegram keeps the plain emoji in a text and drops the custom one from the message's entities, so the
 * first status that carries one tells ([learnFrom]). A button's icon is dropped without a trace in the answer
 * and the button is left bare, so buttons keep a plain emoji in their text until a status has shown that custom
 * emoji go through.
 */
internal class InterfaceIcons {

    // a fallback whose default presentation is text (⏹, ⏸, 🗑) carries U+FE0F, or a client draws the emoji
    // but leaves it a text glyph's width, crowding the word after it
    enum class Icon(val customEmojiId: String, val fallback: String) {
        STOP("5438505993577213069", "⏹️"),
        PAUSE("5438286619532639132", "⏸️"),
        RESUME("5440413002006309207", "▶️"),
        DELETE("5440671271274718851", "🗑️"),
        REFRESH("5440560431053715320", "🔄"),
        BACK("5440735588409980265", "↩️"),
    }

    private enum class Support { UNKNOWN, AVAILABLE, UNAVAILABLE }

    @Volatile
    private var support = Support.UNKNOWN

    /**
     * The running line of a status: the activity's animation before [label], or, without custom emoji or
     * without markup ([html] false), its plain emoji with an ellipsis after, since nothing else moves.
     */
    fun running(activity: ToolActivity, label: String, html: Boolean): String =
        if (html && support != Support.UNAVAILABLE)
            "${tag(activityEmojiId(activity), activityFallback(activity))} $label"
        else
            "${activityFallback(activity)} $label…"

    /** A step the turn has finished, marked as done. */
    fun done(label: String, html: Boolean): String =
        if (html && support != Support.UNAVAILABLE) "${tag(DONE_ID, DONE_FALLBACK)} $label" else "$PLAIN_DONE $label"

    /** Settles whether custom emoji go through, from [sent], a message whose text carried one of ours. */
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

        private const val TAG_START = "<tg-emoji emoji-id=\""

        private const val DONE_ID = "5440465039830067986"
        private const val DONE_FALLBACK = "✅"
        private const val PLAIN_DONE = "✓"
        private const val CUSTOM_EMOJI_ENTITY = "custom_emoji"

        private val log = KotlinLogging.logger {}

        /** Whether [html] carries a custom emoji of ours, so that telegram's answer to it tells [learnFrom] something. */
        fun carriesCustomEmoji(html: String): Boolean = TAG_START in html

        private fun tag(id: String, fallback: String): String = "$TAG_START$id\">$fallback</tg-emoji>"

        private fun activityEmojiId(activity: ToolActivity): String =
            when (activity) {
                ToolActivity.WRITING -> "5440690585742652090"
                ToolActivity.SEARCHING_WEB -> "5440701915866376065"
                ToolActivity.READING_PAGE -> "5440848159502804499"
                ToolActivity.READING_CHANNEL -> "5440843220290417556"
                ToolActivity.READING_TRANSCRIPT -> "5440707366179877984"
                ToolActivity.READING_CHAT_LOG -> "5440581923070062372"
                ToolActivity.SEARCHING_IMAGES -> "5438273266479313313"
                ToolActivity.SEARCHING_GIF -> "5440759751895983297"
                ToolActivity.DRAWING -> "5440536078589142628"
                ToolActivity.FILMING -> "5440816522773707194"
                ToolActivity.RUNNING_CODE -> "5440474982679356206"
                ToolActivity.LOOKING_AT_IMAGE -> "5440503862039458338"
                ToolActivity.WATCHING_VIDEO -> "5440907009144695850"
                ToolActivity.DOWNLOADING_VIDEO -> "5438464087581307668"
                ToolActivity.DOWNLOADING_AUDIO -> "5438548346249722160"
                ToolActivity.SENDING_FILE -> "5440634502059700646"
                ToolActivity.SPEAKING -> "5438239254633296302"
                ToolActivity.REMEMBERING -> "5440608491737751577"
                ToolActivity.MANAGING_TASKS -> "5440648276019814839"
            }

        // the same in every language, so it lives here rather than in each `Messages`
        fun activityFallback(activity: ToolActivity): String =
            when (activity) {
                ToolActivity.WRITING -> "✍️"
                ToolActivity.SEARCHING_WEB -> "🌐"
                ToolActivity.READING_PAGE -> "📄"
                ToolActivity.READING_CHANNEL -> "📢"
                ToolActivity.READING_TRANSCRIPT -> "📝"
                ToolActivity.READING_CHAT_LOG -> "📜"
                ToolActivity.SEARCHING_IMAGES -> "🖼️"
                ToolActivity.SEARCHING_GIF -> "🎞️"
                ToolActivity.DRAWING -> "🎨"
                ToolActivity.FILMING -> "🎥"
                ToolActivity.RUNNING_CODE -> "💻"
                ToolActivity.LOOKING_AT_IMAGE -> "👀"
                ToolActivity.WATCHING_VIDEO -> "📺"
                ToolActivity.DOWNLOADING_VIDEO -> "🎬"
                ToolActivity.DOWNLOADING_AUDIO -> "🎵"
                ToolActivity.SENDING_FILE -> "📎"
                ToolActivity.SPEAKING -> "🎙️"
                ToolActivity.REMEMBERING -> "🧠"
                ToolActivity.MANAGING_TASKS -> "⏰"
            }
    }
}
