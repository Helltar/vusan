package com.helltar.vusan.tools.reminders

internal object ReminderToolDescriptions {

    const val SCHEDULE_REMINDER =
        "Schedule a future task you (the bot) will execute autonomously at the given time. " +
            """Use this whenever the user says "remind me", "send X tomorrow", "every Thursday at 9", """ +
            """"in 15 minutes", "first of every month", etc. """ +
            "At fire time the `prompt` is replayed back to you as a new turn — write it as a clear imperative " +
            """describing what to do ("send a capybara photo and a brief summary of fresh tech news"), """ +
            """not as a question or a meta-note ("remind me to ..."). """ +
            "The user keeps interacting normally while reminders run in the background."

    const val SCHEDULE_PROMPT =
        "Imperative task to execute when the reminder fires, in the user's language. " +
            "Multiple actions are fine — they will be run via tool calls just like a normal turn. " +
            "Examples: `send fresh tech news`, `send 3 capybara photos and tell me a joke about them`. " +
            "Don't write `remind me to ...` — write what should actually happen."

    const val SCHEDULE_WHEN_LOCAL =
        "First fire time as ISO local datetime WITHOUT timezone offset, e.g. `2026-05-25T09:00`. " +
            """Resolve relative phrases ("in 15 minutes", "tomorrow at 9", "next Thursday at 18:00") """ +
            "against the `Current time` shown in your system context. " +
            "For weekly recurrence pick the correct upcoming weekday; for monthly pick the correct day-of-month."

    const val SCHEDULE_REPEAT =
        "Recurrence: `ONCE` (default), `DAILY`, `WEEKLY` (same weekday each week), " +
            "or `MONTHLY` (same day-of-month each month). " +
            "Anything more complex (workdays only, every N hours) is not supported — use `ONCE` and re-schedule from the next fire."

    const val SCHEDULE_TIMEZONE =
        "IANA timezone name like `Europe/Kyiv` or `America/New_York`. " +
            "Pass it only if the user explicitly mentioned a timezone or city. Otherwise omit — the bot's default is used."

    const val SCHEDULE_TITLE =
        "Short human label shown in `listReminders`. Omit if the prompt itself is short and self-explanatory."

    const val LIST_REMINDERS =
        "List the user's active reminders (id, next fire time, recurrence, title/prompt). " +
            """Call when the user asks "what reminders do I have" or before cancelling one."""

    const val CANCEL_REMINDER =
        "Cancel a reminder by its numeric id (from `listReminders`). " +
            "If the user names a reminder without giving an id, call `listReminders` first to look it up."

    const val CANCEL_ID = "Numeric id of the reminder to cancel."
}
