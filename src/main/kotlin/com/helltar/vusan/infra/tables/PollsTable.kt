package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * The polls and quizzes the bot has put in a group, kept so that an answer to one can be read back.
 *
 * A `poll_answer` update names the poll and the option numbers and nothing else — not the question,
 * not what the options said, not the chat. Without this row an answer is an integer nobody can place.
 */
object PollsTable : Table("polls") {

    /** Telegram's poll id, which is what a `poll_answer` update arrives carrying. */
    val pollId = varchar("poll_id", 64)

    val chatId = long("chat_id")

    /** The option texts in the order Telegram numbers them, one per line. */
    val optionList = text("options")

    /** The answer that counts as right, for a quiz; `null` for an ordinary poll. */
    val correctOptionIndex = integer("correct_option_index").nullable()

    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    override val primaryKey = PrimaryKey(pollId)

    init {
        index(false, createdAt)
    }
}
