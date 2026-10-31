package com.helltar.vusan.tools.leavealone

import com.helltar.vusan.agent.presence.LeaveAloneRepository
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.request.SenderContext
import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.tools.suspendToolGuard
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

// a request without a length holds for the rest of its day, at least half a day: said late in the evening,
// the day's end would lapse before the person is asleep. longer than a week means "never", which is a mute
// for the person to lift, not a row to keep
private val MIN_DEFAULT_LENGTH = 12.hours
private val MAX_LENGTH = 7.days

private val END = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private const val MAX_NAME_CHARS = 60

/**
 * What a person says to stop the bot writing to them, recorded so code keeps the promise: no look reacts to
 * or answers them, no follow-up is guessed to be for them, until the request ends or they lift it. In a
 * private chat it stops follow-ups, the one thing sent there unasked. Registered only for a sender who is one
 * person.
 */
class LeaveAloneTools(
    private val requests: LeaveAloneRepository,
    private val context: RequestContext,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Instant = Instant::now,
) : ToolSet {

    @Tool(LeaveAloneToolDescriptions.LEAVE_ALONE)
    suspend fun leaveAlone(
        @Arg(LeaveAloneToolDescriptions.HOURS)
        hours: Int? = null,
    ): String = suspendToolGuard {
        require(hours == null || hours >= 0) { "hours must be 0 or more" }

        if (hours == 0) {
            return@suspendToolGuard if (requests.lift(context.chatRef, context.user)) {
                "Lifted: you may write to ${context.sender.shownAs} in this chat again as before."
            } else {
                "There was no request from ${context.sender.shownAs} to lift."
            }
        }

        val now = clock()
        val until = hours?.let { now.plus(it.hours.coerceAtMost(MAX_LENGTH).toJavaDuration()) } ?: defaultEnd(now)

        requests.store(context.chatRef, context.user, until)

        "Noted: you leave ${context.sender.shownAs} alone in this chat until ${END.format(until.atZone(zone))}. " +
            "Nothing of yours reaches them unasked until then — no reaction to their messages, no reply, no line meant " +
            "for them; a message they send you themselves is answered as usual. Answer this one as you see fit, " +
            "or stay silent."
    }

    private fun defaultEnd(now: Instant): Instant {
        val endOfDay = LocalDate.ofInstant(now, zone).plusDays(1).atStartOfDay(zone).toInstant()

        return maxOf(endOfDay, now.plus(MIN_DEFAULT_LENGTH.toJavaDuration()))
    }
}

// the name is user text, bounded like everything else of theirs in a prompt
private val SenderContext.shownAs: String
    get() = (displayName ?: username)?.collapseWhitespaceAndCap(MAX_NAME_CHARS) ?: "this person"
