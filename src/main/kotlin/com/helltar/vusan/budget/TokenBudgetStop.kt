package com.helltar.vusan.budget

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

/** Why an LLM call cannot happen right now, and how long until that changes. */
sealed interface TokenBudgetStop {

    val untilReset: Duration

    /** The day's whole budget is gone. Nobody spends anything until it resets. */
    data class DayBudget(override val untilReset: Duration) : TokenBudgetStop

    /** The day is running low and this user is already over their share of it. Others may still spend. */
    data class UserShare(override val untilReset: Duration) : TokenBudgetStop
}

/** Thrown instead of starting an LLM call once [stop] applies. */
class TokenBudgetExhaustedException(val stop: TokenBudgetStop) :
    RuntimeException("token budget stop: $stop")

/**
 * The budget stop behind a failure, or `null` when it failed for some other reason. Callers that treat a
 * failure as a verdict on the work itself — a retry counter, a "give up on this one" flag — have to tell the
 * two apart: the budget will be back tomorrow, and the work with it.
 */
fun Throwable.tokenBudgetStop(): TokenBudgetStop? =
    generateSequence(this) { it.cause }
        .filterIsInstance<TokenBudgetExhaustedException>()
        .firstOrNull()
        ?.stop

/**
 * Whose share of the budget the LLM calls made under this coroutine come out of.
 *
 * A turn spends tokens in places that never see the request: a history recap, a group-day digest, a vision
 * tool reading an image. Carrying the author in the coroutine context charges all of it to them without
 * every one of those call sites having to pass a user id it has no other use for. Work started outside a
 * turn — the sticker description worker — carries no owner and answers to the day's ceiling alone.
 */
class BudgetOwner(val userId: Long) : AbstractCoroutineContextElement(BudgetOwner) {
    companion object Key : CoroutineContext.Key<BudgetOwner>
}

internal suspend fun currentBudgetOwner(): Long? = coroutineContext[BudgetOwner]?.userId
