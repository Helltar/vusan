package com.helltar.vusan.budget

import ai.koog.prompt.executor.model.PromptExecutor
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.config.TokenBudgetConfig
import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.TokenUsageTable
import com.helltar.vusan.infra.tables.TokenUserSpendTable
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

// how far back a user counts as one of the people the budget is shared with. long enough to cover someone
// who uses the bot on weekdays only, short enough that a visitor from last month reserves nothing.
private const val ACTIVE_WINDOW_DAYS = 7L

// per-user rows outlive the sharing window by enough to stay diagnosable, then go.
private const val USER_SPEND_RETENTION_DAYS = 30L

/**
 * The bot's daily allowance of LLM tokens, counted across everything it does — chat turns, history recaps,
 * group-log digests, vision — and reset at midnight in the configured zone.
 *
 * Two rules guard it. The day's total is a hard ceiling nobody passes. On top of that, once the day is
 * mostly spent, a single person can no longer take the rest: whoever is already over their fair share waits
 * while everyone below theirs keeps working. The share is `budget / people active in the last week`, so a
 * chat member who never uses the bot reserves nothing, and the split follows who actually shows up.
 *
 * The ceiling is deliberately soft: it is checked before a call, never mid-call, so the day's last turn can
 * overshoot by one turn's worth of tokens. Enforcing it any harder would mean cutting a reply in half.
 *
 * With no `LLM_DAILY_TOKEN_BUDGET` set the whole thing is inert: nothing is counted, nothing is stored, and
 * [meter] hands the executor back untouched.
 */
class TokenBudget(
    private val config: TokenBudgetConfig = TokenBudgetConfig(),
    private val clock: Clock = Clock.systemUTC()
) {

    private companion object {
        val log = KotlinLogging.logger {}
    }

    private val mutex = Mutex()
    private var day: LocalDate? = null
    private var spent = DailySpend()
    private var spentByUser = mutableMapOf<Long, DailySpend>()

    // how many people the day is split between: everyone seen in the last ACTIVE_WINDOW_DAYS.
    private var shares = 1

    /** Wraps [executor] so that every LLM call it makes counts against this budget. */
    fun meter(executor: PromptExecutor): PromptExecutor =
        if (config.dailyTokens == null) executor else BudgetedPromptExecutor(executor, this)

    /**
     * Why [userId] cannot spend right now, or `null` when they can. A `null` user is the bot's own
     * background work (describing stickers, digesting a group day): it belongs to nobody's share and
     * answers to the day's ceiling alone.
     */
    suspend fun stopFor(userId: Long?): TokenBudgetStop? {
        val limit = config.dailyTokens ?: return null
        val now = clock.instant()

        return mutex.withLock {
            loadIfNewDay(now)

            when {
                spent.totalTokens >= limit -> TokenBudgetStop.DayBudget(untilReset(now))
                userId == null || spent.totalTokens < sharingStartsAt(limit) -> null
                (spentByUser[userId]?.totalTokens ?: 0L) >= fairShare(limit) -> TokenBudgetStop.UserShare(untilReset(now))
                else -> null
            }
        }
    }

    /**
     * Adds one completed LLM call to today's spend, and to [userId]'s share of it. Unknown token counts
     * (`null`) count as zero.
     */
    suspend fun record(userId: Long?, inputTokens: Int?, outputTokens: Int?) {
        val limit = config.dailyTokens ?: return
        val call = DailySpend((inputTokens ?: 0).toLong(), (outputTokens ?: 0).toLong())
        if (call.totalTokens <= 0) return

        val now = clock.instant()

        mutex.withLock {
            loadIfNewDay(now)

            val today = checkNotNull(day)
            val before = spent.totalTokens

            spent += call
            store(today, spent, now)

            if (userId != null) {
                val updated = spentByUser.getOrElse(userId) { DailySpend() } + call
                spentByUser[userId] = updated
                storeUser(today, userId, updated, now)

                // someone new today is one more person to split the day with, without waiting for a reload.
                shares = maxOf(shares, spentByUser.size)
            }

            if (before < limit && spent.totalTokens >= limit) {
                log.warn {
                    "daily token budget spent: day=$today tokens=${spent.totalTokens}/$limit " +
                            "resetsIn=${untilReset(now)}"
                }
            }
        }
    }

    // the point in the day where a single person stops being able to take the rest of it. below it the
    // budget is first come, first served, so a quiet day never makes anyone feel a limit at all.
    private fun sharingStartsAt(limit: Long): Long =
        limit * config.fairSharePercent / 100

    private fun fairShare(limit: Long): Long =
        limit / shares

    // today's spend, reloaded from storage whenever the budget day changes — at startup and at every reset.
    private suspend fun loadIfNewDay(now: Instant) {
        val today = LocalDate.ofInstant(now, config.zone)
        if (day == today) return

        spent = load(today)
        spentByUser = loadUsers(today)
        shares = maxOf(countRecentUsers(today), spentByUser.size, 1)
        day = today

        pruneOldUserSpend(today)

        log.info {
            "token budget day: day=$today spent=${spent.totalTokens} limit=${config.dailyTokens} " +
                    "shares=$shares fairShare=${config.dailyTokens?.let { fairShare(it) }}"
        }
    }

    private fun untilReset(now: Instant): Duration =
        java.time.Duration
            .between(now, LocalDate.ofInstant(now, config.zone).plusDays(1).atStartOfDay(config.zone).toInstant())
            .toKotlinDuration()

    private suspend fun load(day: LocalDate): DailySpend =
        readOrElse("the token budget spent on day=$day", DailySpend()) {
            TokenUsageTable
                .selectAll()
                .where { TokenUsageTable.day eq day.toString() }
                .singleOrNull()
                ?.let { DailySpend(it[TokenUsageTable.inputTokens], it[TokenUsageTable.outputTokens]) }
                ?: DailySpend()
        }

    private suspend fun loadUsers(day: LocalDate): MutableMap<Long, DailySpend> =
        readOrElse("today's per-user token spend for day=$day", mutableMapOf()) {
            TokenUserSpendTable
                .selectAll()
                .where { TokenUserSpendTable.day eq day.toString() }
                .associateTo(mutableMapOf()) {
                    it[TokenUserSpendTable.userId] to
                            DailySpend(it[TokenUserSpendTable.inputTokens], it[TokenUserSpendTable.outputTokens])
                }
        }

    // the people the day is shared with: everyone who spent anything in the sharing window. counting who
    // actually used the bot rather than who is allowed to keeps an idle chat member from shrinking the
    // share of the people who are here.
    private suspend fun countRecentUsers(today: LocalDate): Int =
        readOrElse("the recently active users for day=$today", 1) {
            val since = today.minusDays(ACTIVE_WINDOW_DAYS - 1).toString()

            TokenUserSpendTable
                .select(TokenUserSpendTable.userId)
                .where { TokenUserSpendTable.day greaterEq since }
                .withDistinct()
                .count()
                .toInt()
        }

    // the running total is written in full rather than incremented: this process owns it, so a lost write
    // costs at most the calls since the last successful one, and never leaves the row double-counted.
    private suspend fun store(day: LocalDate, spend: DailySpend, now: Instant) {
        write("the token budget for day=$day") {
            TokenUsageTable.upsert(TokenUsageTable.day) {
                it[TokenUsageTable.day] = day.toString()
                it[TokenUsageTable.inputTokens] = spend.inputTokens
                it[TokenUsageTable.outputTokens] = spend.outputTokens
                it[TokenUsageTable.updatedAt] = now
            }
        }
    }

    private suspend fun storeUser(day: LocalDate, userId: Long, spend: DailySpend, now: Instant) {
        write("the token spend of user=$userId for day=$day") {
            TokenUserSpendTable.upsert(TokenUserSpendTable.day, TokenUserSpendTable.userId) {
                it[TokenUserSpendTable.day] = day.toString()
                it[TokenUserSpendTable.userId] = userId
                it[TokenUserSpendTable.inputTokens] = spend.inputTokens
                it[TokenUserSpendTable.outputTokens] = spend.outputTokens
                it[TokenUserSpendTable.updatedAt] = now
            }
        }
    }

    private suspend fun pruneOldUserSpend(today: LocalDate) {
        write("the expired per-user token spend") {
            TokenUserSpendTable.deleteWhere {
                TokenUserSpendTable.day less today.minusDays(USER_SPEND_RETENTION_DAYS).toString()
            }
        }
    }

    // budget bookkeeping must never take a turn down with it: a failed read starts the day from what is
    // known, a failed write keeps the in-memory count and retries with the next call.
    private suspend fun <T> readOrElse(what: String, fallback: T, read: () -> T): T =
        runCatching { dbTransaction { read() } }
            .getOrElse {
                it.rethrowIfCancellation()
                log.warn(it) { "failed to load $what" }
                fallback
            }

    private suspend fun write(what: String, statement: () -> Unit) {
        runCatching { dbTransaction { statement() } }
            .onFailure {
                it.rethrowIfCancellation()
                log.warn(it) { "failed to persist $what; keeping the in-memory count" }
            }
    }
}

private data class DailySpend(val inputTokens: Long = 0, val outputTokens: Long = 0) {

    val totalTokens: Long
        get() = inputTokens + outputTokens

    operator fun plus(other: DailySpend): DailySpend =
        DailySpend(inputTokens + other.inputTokens, outputTokens + other.outputTokens)
}
