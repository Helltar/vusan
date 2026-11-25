package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * The widgets the bot sent and the state it last wrote to each. A press names only the chat and message (or,
 * from a forwarded copy, only the shared widget), and the Bot API reads no state back, so what the widget is
 * for and what it shows are written down here.
 *
 * Telegram-owned, like `telegram_polls`: only `telegram/` uses it.
 */
object TelegramWidgetsTable : Table("telegram_widgets") {

    val chatId = long("chat_id")

    val messageId = integer("message_id")

    /** The forum topic it was posted in; a press does not say, and the answer belongs there. */
    val threadId = integer("thread_id").nullable()

    /** Telegram's id for a shared widget, the same in every message showing it; `null` for one of its own. */
    val widgetId = varchar("widget_id", 64).nullable()

    /** `WidgetRequests`, by name. */
    val requests = varchar("requests", 8)

    val caption = text("caption")

    val chatData = text("chat_data").nullable()

    val publicData = text("public_data").nullable()

    /** Raised with every state the bot writes, so a widget can drop a state older than one it holds. */
    val revision = integer("revision").default(0)

    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(chatId, messageId)

    init {
        index(false, widgetId)
        index(false, createdAt)
    }
}

/** Each person's latest choice in a counting widget, keyed on the message that first showed it. */
object TelegramWidgetVotesTable : Table("telegram_widget_votes") {

    val chatId = long("chat_id")

    val messageId = integer("message_id")

    val userId = long("user_id")

    val choice = varchar("choice", 64)

    override val primaryKey = PrimaryKey(chatId, messageId, userId)
}
