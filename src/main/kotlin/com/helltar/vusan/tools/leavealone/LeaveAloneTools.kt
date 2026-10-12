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

// a request with no length holds for the day it was made on, and for half a day at the least: said late in
// the evening, the end of the day would let it lapse before the person is even asleep. longer than a week
// is somebody who means "never", and never is a mute for a person to lift, not a row to keep
private val MIN_DEFAULT_LENGTH = 12.hours
private val MAX_LENGTH = 7.days

private val END = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private const val MAX_NAME_CHARS = 60

/**
 * What a person says when they want the bot to stop writing to them, recorded so that code keeps the
 * promise: no look reacts to them or answers them, no follow-up of the bot's is guessed to be for them,
 * until the request ends or they lift it. In a private chat that stops its follow-ups, the one thing the bot
 * sends there unasked. Registered only for a sender who is one person: a shared account is nobody to leave
 * alone.
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

// the name is the person's own text, bounded like everything else of theirs that lands in a prompt
private val SenderContext.shownAs: String
    get() = (displayName ?: username)?.collapseWhitespaceAndCap(MAX_NAME_CHARS) ?: "this person"
