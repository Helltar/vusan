package com.helltar.vusan.budget

import ai.koog.prompt.executor.model.PromptExecutor
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.config.TokenBudgetConfig
import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.TokenUsageTable
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

/**
 * The bot's daily allowance of LLM tokens, counted across everything it does — chat turns, history recaps,
 * group-log digests, vision — and reset at midnight in the configured zone.
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

    /** Wraps [executor] so that every LLM call it makes counts against this budget. */
    fun meter(executor: PromptExecutor): PromptExecutor =
        if (config.dailyTokens == null) executor else BudgetedPromptExecutor(executor, this)

    /** How long until the budget resets, or `null` while today still has tokens left. */
    suspend fun exhaustedFor(): Duration? {
        val limit = config.dailyTokens ?: return null
        val now = clock.instant()

        return mutex.withLock {
            if (spentToday(now).totalTokens < limit) null else untilReset(now)
        }
    }

    /** Adds one completed LLM call to today's spend. Unknown token counts (`null`) count as zero. */
    suspend fun record(inputTokens: Int?, outputTokens: Int?) {
        val limit = config.dailyTokens ?: return
        val call = DailySpend((inputTokens ?: 0).toLong(), (outputTokens ?: 0).toLong())
        if (call.totalTokens <= 0) return

        val now = clock.instant()

        mutex.withLock {
            val before = spentToday(now).totalTokens
            val today = checkNotNull(day)

            spent += call
            store(today, spent, now)

            if (before < limit && spent.totalTokens >= limit) {
                log.warn {
                    "daily token budget spent: day=$today tokens=${spent.totalTokens}/$limit " +
                            "resetsIn=${untilReset(now)}"
                }
            }
        }
    }

    // today's spend, reloaded from storage whenever the budget day changes — at startup and at every reset.
    private suspend fun spentToday(now: Instant): DailySpend {
        val today = LocalDate.ofInstant(now, config.zone)
        if (day == today) return spent

        spent = load(today)
        day = today

        log.info { "token budget day: day=$today spent=${spent.totalTokens} limit=${config.dailyTokens}" }

        return spent
    }

    private fun untilReset(now: Instant): Duration =
        java.time.Duration
            .between(now, LocalDate.ofInstant(now, config.zone).plusDays(1).atStartOfDay(config.zone).toInstant())
            .toKotlinDuration()

    private suspend fun load(day: LocalDate): DailySpend =
        runCatching {
            dbTransaction {
                TokenUsageTable
                    .selectAll()
                    .where { TokenUsageTable.day eq day.toString() }
                    .singleOrNull()
                    ?.let { DailySpend(it[TokenUsageTable.inputTokens], it[TokenUsageTable.outputTokens]) }
                    ?: DailySpend()
            }
        }.getOrElse {
            it.rethrowIfCancellation()
            log.warn(it) { "failed to load the token budget spent on day=$day; starting the day from zero" }
            DailySpend()
        }

    // the running total is written in full rather than incremented: this process owns it, so a lost write
    // costs at most the calls since the last successful one, and never leaves the row double-counted.
    private suspend fun store(day: LocalDate, spend: DailySpend, now: Instant) {
        runCatching {
            dbTransaction {
                TokenUsageTable.upsert(TokenUsageTable.day) {
                    it[TokenUsageTable.day] = day.toString()
                    it[TokenUsageTable.inputTokens] = spend.inputTokens
                    it[TokenUsageTable.outputTokens] = spend.outputTokens
                    it[TokenUsageTable.updatedAt] = now
                }
            }
        }.onFailure {
            it.rethrowIfCancellation()
            log.warn(it) { "failed to persist the token budget for day=$day; keeping the in-memory count" }
        }
    }
}

private data class DailySpend(val inputTokens: Long = 0, val outputTokens: Long = 0) {

    val totalTokens: Long
        get() = inputTokens + outputTokens

    operator fun plus(other: DailySpend): DailySpend =
        DailySpend(inputTokens + other.inputTokens, outputTokens + other.outputTokens)
}
