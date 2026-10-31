package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * The polls and quizzes the bot sent to groups, kept so answers can be read back. A `poll_answer` names only
 * the poll and option numbers; without this row an answer is an integer nobody can place.
 *
 * Telegram-owned, so [chatId] has no `platform` beside it: only `telegram/` uses this table, and another
 * messenger would need its own mechanism.
 */
object TelegramPollsTable : Table("telegram_polls") {

    /** Telegram's poll id, which a `poll_answer` carries. */
    val pollId = varchar("poll_id", 64)

    val chatId = long("chat_id")

    /** The option texts in telegram's numbering order, one per line. */
    val optionList = text("options")

    /** The right answer of a quiz; `null` for an ordinary poll. */
    val correctOptionIndex = integer("correct_option_index").nullable()

    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(pollId)

    init {
        index(false, createdAt)
    }
}
