package com.helltar.vusan.telegram

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.TelegramPollsTable
import com.helltar.vusan.outbox.BotOutput
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/** A poll the bot sent, in the form needed to read an answer back. */
data class SentPoll(
    val chatId: Long,
    val options: List<String>,
    val correctOptionIndex: Int?,
)

/**
 * What the bot's own polls said, so answers mean something. A `poll_answer` carries a poll id and option
 * numbers; the chat, the option texts and the right answer were known only at sending, so they are written
 * down then.
 *
 * Only group polls are kept: the transcript does not cover private chats.
 */
class PollRegistry(private val retention: Duration = DEFAULT_RETENTION) {

    suspend fun remember(pollId: String, chatId: Long, output: BotOutput) {
        val (options, correctOptionIndex) =
            when (output) {
                is BotOutput.Quiz -> output.options to output.correctOptionIndex
                is BotOutput.Poll -> output.options to null
                else -> return
            }

        runCatching {
            dbTransaction {
                TelegramPollsTable.insertIgnore {
                    it[TelegramPollsTable.pollId] = pollId
                    it[TelegramPollsTable.chatId] = chatId
                    it[TelegramPollsTable.optionList] = options.joinToString(OPTION_SEPARATOR)
                    it[TelegramPollsTable.correctOptionIndex] = correctOptionIndex
                }
            }
        }.onFailure {
            it.rethrowIfCancellation()
            log.warn(it) { "failed to remember poll id=[$pollId] in chat=$chatId; answers to it will not be recorded" }
        }
    }

    /**
     * Drops polls past [retention] at [now] and returns how many. Answers arrive while people still look at a
     * poll, and the row only feeds the transcript.
     */
    suspend fun pruneExpired(now: Instant): Int = dbTransaction {
        TelegramPollsTable.deleteWhere {
            TelegramPollsTable.createdAt less now.minusMillis(retention.inWholeMilliseconds)
        }
    }

    suspend fun find(pollId: String): SentPoll? =
        runCatching {
            dbTransaction {
                TelegramPollsTable
                    .select(TelegramPollsTable.chatId, TelegramPollsTable.optionList, TelegramPollsTable.correctOptionIndex)
                    .where { TelegramPollsTable.pollId eq pollId }
                    .singleOrNull()
                    ?.let {
                        SentPoll(
                            chatId = it[TelegramPollsTable.chatId],
                            options = it[TelegramPollsTable.optionList].split(OPTION_SEPARATOR),
                            correctOptionIndex = it[TelegramPollsTable.correctOptionIndex],
                        )
                    }
            }
        }.getOrElse {
            it.rethrowIfCancellation()
            log.warn(it) { "failed to look up poll id=[$pollId]" }
            null
        }

    private companion object {
        // telegram caps an option at 100 characters and a poll at 12 options, and an option cannot contain a
        // newline.
        const val OPTION_SEPARATOR = "\n"

        // long enough for a poll left open over a holiday, short enough not to keep every question ever
        // asked.
        val DEFAULT_RETENTION = 30.days

        val log = KotlinLogging.logger {}
    }
}
