package com.helltar.vusan.tools.tasks

internal object TaskToolDescriptions {

    const val SCHEDULE_TASK =
        "Schedule a future task you (the bot) will execute autonomously at the given time. " +
            """Use this whenever the user says "remind me", "send X tomorrow", "every Thursday at 9", """ +
            """"in 15 minutes", "first of every month", etc. """ +
            "At fire time the `prompt` is replayed back to you as a new turn — write it as a clear imperative " +
            """describing what to do ("send a capybara photo and a brief summary of fresh tech news"), """ +
            """not as a question or a meta-note ("remind me to ..."). """ +
            "The user keeps interacting normally while tasks run in the background."

    const val SCHEDULE_PROMPT =
        "Imperative task to execute when it fires, in the user's language. " +
            "Multiple actions are fine — they will be run via tool calls just like a normal turn. " +
            "Examples: `send fresh tech news`, `send 3 capybara photos and tell me a joke about them`. " +
            "Don't write `remind me to ...` — write what should actually happen."

    const val SCHEDULE_SPEC =
        "When the task fires. Pick ONE form and translate the user's phrasing into it:\n" +
            "- `once <ISO local datetime>` — a single fire, e.g. `once 2026-05-30T09:00`. " +
            """Resolve relative phrases ("in 15 minutes", "tomorrow at 9") against the `Current time` in your system context.""" + "\n" +
            "- `every <interval>` — a fixed repeating interval, e.g. `every 90m`, `every 2h`, `every 1h30m` (minimum 5 minutes). " +
            "Use this for plain intervals not tied to a wall-clock time; first fire is one interval from now.\n" +
            "- `cron <UNIX 5-field expr>` — `minute hour day-of-month month day-of-week`, for anything tied to clock times or specific days. " +
            "day-of-week: `0`/`7`=Sunday, `1`=Monday … `6`=Saturday. Examples: " +
            "`cron 0 9 * * *` (daily 09:00), `cron 0 18 * * 1-5` (weekdays 18:00), " +
            "`cron 0 9 * * 1,3,5` (Mon/Wed/Fri 09:00), `cron 0 0 1,15 * *` (1st & 15th at midnight), " +
            "`cron 0 9 * * 4` (every Thursday 09:00). " +
            "Cron times are evaluated in the task's timezone."

    const val SCHEDULE_TIMEZONE =
        "IANA timezone name like `Europe/Kyiv` or `America/New_York`. " +
            "Pass it only if the user explicitly mentioned a timezone or city. Otherwise omit — the bot's default is used."

    const val SCHEDULE_TITLE =
        "Short human label shown in `listTasks`. Omit if the prompt itself is short and self-explanatory."

    const val LIST_TASKS =
        "List the user's active scheduled tasks (id, next fire time, recurrence, title/prompt). " +
            """Call when the user asks "what do I have scheduled" or before cancelling one."""

    const val CANCEL_TASK =
        "Cancel a scheduled task by its numeric id (from `listTasks`). " +
            "If the user names a task without giving an id, call `listTasks` first to look it up."

    const val CANCEL_ID = "Numeric id of the scheduled task to cancel."
}
