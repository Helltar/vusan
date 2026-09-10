package com.helltar.vusan.telegram

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.PollsTable
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

/** A poll the bot put in a chat, in the form an answer to it needs to be read back. */
data class SentPoll(
    val chatId: Long,
    val options: List<String>,
    val correctOptionIndex: Int?
)

/**
 * What the bot's own polls said, so that answers to them mean something.
 *
 * A `poll_answer` update carries a poll id and option numbers. Everything that makes those numbers
 * readable — which chat, what the options said, which one was right — was known only at the moment
 * the poll was sent, so it is written down there.
 *
 * Only polls put in a group are kept: the transcript this feeds does not cover private chats, and a
 * poll nobody but the sender can answer has nothing to report.
 */
class PollRegistry(private val retention: Duration = DEFAULT_RETENTION) {

    private companion object {
        // telegram caps a poll option at 100 characters and a poll at 12 of them; the joined form has
        // room to spare, and an option cannot contain the newline that separates them.
        const val OPTION_SEPARATOR = "\n"

        // long enough that a poll left open over a holiday still resolves its answers, short enough
        // that the table is not a permanent record of every question the bot ever asked.
        val DEFAULT_RETENTION = 30.days

        val log = KotlinLogging.logger {}
    }

    suspend fun remember(pollId: String, chatId: Long, output: BotOutput) {
        val (options, correctOptionIndex) =
            when (output) {
                is BotOutput.Quiz -> output.options to output.correctOptionIndex
                is BotOutput.Poll -> output.options to null
                else -> return
            }

        runCatching {
            dbTransaction {
                PollsTable.insertIgnore {
                    it[PollsTable.pollId] = pollId
                    it[PollsTable.chatId] = chatId
                    it[PollsTable.optionList] = options.joinToString(OPTION_SEPARATOR)
                    it[PollsTable.correctOptionIndex] = correctOptionIndex
                }
            }
        }.onFailure {
            it.rethrowIfCancellation()
            log.warn(it) { "failed to remember poll id=[$pollId] in chat=$chatId; answers to it will not be recorded" }
        }
    }

    /**
     * Drops the polls past [retention], and answers how many. Nothing waits for a poll that old: an
     * answer to one arrives while people are still looking at it, and the row only feeds the transcript.
     */
    suspend fun pruneExpired(): Int = dbTransaction {
        PollsTable.deleteWhere {
            PollsTable.createdAt less Instant.now().minusMillis(retention.inWholeMilliseconds)
        }
    }

    suspend fun find(pollId: String): SentPoll? =
        runCatching {
            dbTransaction {
                PollsTable
                    .select(PollsTable.chatId, PollsTable.optionList, PollsTable.correctOptionIndex)
                    .where { PollsTable.pollId eq pollId }
                    .singleOrNull()
                    ?.let {
                        SentPoll(
                            chatId = it[PollsTable.chatId],
                            options = it[PollsTable.optionList].split(OPTION_SEPARATOR),
                            correctOptionIndex = it[PollsTable.correctOptionIndex]
                        )
                    }
            }
        }.getOrElse {
            it.rethrowIfCancellation()
            log.warn(it) { "failed to look up poll id=[$pollId]" }
            null
        }
}
