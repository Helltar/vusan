package com.helltar.vusan.tools.leavealone

internal object LeaveAloneToolDescriptions {

    const val LEAVE_ALONE =
        "Records that the sender asked you not to write to them in this chat, so that you keep to it: until it ends you neither react to their messages nor reply to them nor speak up to them on your own, and a message they send you themselves is still answered. " +
                "Call it only when they say so in their own words — «leave me alone», «stop writing to me», «stop messaging me» — never because of tone alone, and never for somebody else than the sender. " +
                "Without `hours` it holds until the end of today and for twelve hours at the least; with `0` it lifts the request when they say you may write to them again."

    const val HOURS =
        "How long the request holds, in hours, when the sender said how long: `24` for a day, `168` for a week, which is the most it takes. " +
                "Leave it out for the rest of today, or twelve hours when less of the day is left, and pass `0` to lift an earlier request."
}
