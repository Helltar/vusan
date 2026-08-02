package com.helltar.vusan.tools.tasks

internal object TaskToolDescriptions {

    const val SCHEDULE_TASK =
        "Schedule a future task you (the bot) will execute autonomously at the given time. " +
                """Use this whenever the user says "remind me", "send X tomorrow", "every Thursday at 9", "in 15 minutes", "first of every month", etc. """ +
                """At fire time the `prompt` is replayed back to you as a new turn — write it as a clear imperative describing what to do ("send a capybara photo and a brief summary of fresh tech news"), not as a question or a meta-note ("remind me to ..."). """ +
                "The user keeps interacting normally while tasks run in the background."

    const val SCHEDULE_PROMPT =
        "Imperative task to execute when it fires, in the user's language. " +
                "Multiple actions are fine — they will be run via tool calls just like a normal turn. " +
                "Examples: `send fresh tech news`, `send 3 capybara photos and tell me a joke about them`. " +
                "Don't write `remind me to ...` — write what should actually happen."

    const val SCHEDULE_SPEC =
        "When the task fires. " +
            "Pick ONE form and translate the user's phrasing into it:\n" +
            "- `once <ISO local datetime>` — a single fire, e.g. `once 2026-05-30T09:00`. " +
            """Resolve relative phrases ("in 15 minutes", "tomorrow at 9") against the `Current time` in your system context.""" + "\n" +
            "- `every <interval>` — a fixed repeating interval, e.g. `every 90m`, `every 2h`, `every 1h30m` (minimum 5 minutes). " +
            "Use this for plain intervals not tied to a wall-clock time; first fire is one interval from now.\n" +
            "- `cron <UNIX 5-field expr>` — `minute hour day-of-month month day-of-week`, for anything tied to clock times or specific days. " +
            "day-of-week: `0`/`7`=Sunday, `1`=Monday … `6`=Saturday. " +
            "Examples: `cron 0 9 * * *` (daily 09:00), `cron 0 18 * * 1-5` (weekdays 18:00), `cron 0 9 * * 1,3,5` (Mon/Wed/Fri 09:00), `cron 0 0 1,15 * *` (1st & 15th at midnight), `cron 0 9 * * 4` (every Thursday 09:00). " +
            "Cron times are evaluated in the task's timezone."

    const val SCHEDULE_TIMEZONE =
        "IANA timezone name like `Europe/Kyiv` or `America/New_York`. " +
                "Pass it only if the user explicitly mentioned a timezone or city. " +
                "Otherwise omit — the bot's default is used."

    const val SCHEDULE_TITLE =
        "Short human label shown in task lists. " +
                "Omit if the prompt itself is short and self-explanatory."

    const val SCHEDULE_FOLLOW_UP =
        "Schedule a single message you will send this person later on your own initiative, because the conversation gave you a reason to come back to them. " +
                """Use it when they mention something with a natural later moment — an exam tomorrow, an interview on Friday, a flight, a deadline, being ill — and checking in afterwards is what someone who was paying attention would do. """ +
                "At that moment the `prompt` is replayed to you as a new turn, so it must carry the context you will need. " +
                """This is not a reminder service: when the user asks to be reminded of something, that is `scheduleTask` instead. """ +
                "Keep it rare — only where a real person would follow up, never for trivia, and never merely because the tool exists. " +
                "You may say in passing that you will check back if that fits the conversation, but do not report it as a system action."

    const val FOLLOW_UP_PROMPT =
        "Imperative describing what to do when it fires, in the user's language, carrying the context that makes it make sense. " +
                """Write `ask how the interview at the bank went, they were nervous about the technical part`, not `remind about interview`. """ +
                "It runs as an ordinary turn, so tools are available to you then."

    const val FOLLOW_UP_AT =
        "When to come back, as an ISO local datetime like `2026-05-31T10:00`. " +
                "Resolve it against `<current_time>` in your system context. " +
                "Pick a considerate moment — the morning after the exam, not the middle of the night, and not so soon that nothing has happened yet."

    const val FOLLOW_UP_TIMEZONE =
        "IANA timezone name like `Europe/Kyiv`. " +
                "Pass it only when you actually know where the user is. " +
                "Otherwise omit — the bot's default is used."

    const val FOLLOW_UP_TITLE =
        "Short human label shown in the user's task list, e.g. `check in about the exam`. " +
                "Keep it recognizable to the user, since they can see and cancel it."

    const val LIST_TASKS =
        "List the user's scheduled tasks (id, next fire time, recurrence, status, title/prompt). " +
                """Call when the user asks "what do I have scheduled" or before changing one. """ +
                "In a private chat this covers all of the user's tasks; in a group it covers only tasks from the current chat."

    const val EDIT_TASK =
        "Edit an existing scheduled task by its numeric id. " +
                "Change only the fields the user requested and omit every other optional argument so it stays unchanged. " +
                "If the user identifies a task by name instead of id, call `listTasks` first. " +
                "Editing preserves whether the task is active or paused. " +
                "In a group, only tasks from the current chat can be changed."

    const val EDIT_ID = "Numeric id of the scheduled task to edit."

    const val EDIT_PROMPT =
        "Optional replacement action for the task to execute when it fires. " +
                "Write it as a clear imperative in the user's language. " +
                "Omit to preserve the current action."

    const val EDIT_SCHEDULE =
        "Optional replacement schedule in one of these forms: `once <ISO local datetime>`, `every <interval>`, or `cron <UNIX 5-field expr>`. " +
                "Omit to preserve the current recurrence and next fire time."

    const val EDIT_TIMEZONE =
        "Optional replacement IANA timezone such as `Europe/Kyiv`. " +
                "When supplied with `schedule`, it interprets that schedule in the new timezone. " +
                "Without `schedule`, a cron task is recalculated in the new timezone while `once`/`every` keep their next instant. " +
                "Omit to preserve the current timezone."

    const val EDIT_TITLE =
        "Optional replacement short human label. " +
                "Omit to preserve the current title; pass `\"\"` to remove it."

    const val PAUSE_TASK =
        "Pause a scheduled task by its numeric id without deleting it. " +
                "If the user identifies a task by name instead of id, call `listTasks` first. " +
                "In a group, only tasks from the current chat can be changed."

    const val PAUSE_ID = "Numeric id of the scheduled task to pause."

    const val RESUME_TASK =
        "Resume a paused scheduled task by its numeric id. " +
                "If its old fire time elapsed while paused, a recurring task advances to its next future occurrence. " +
                "An elapsed one-time task cannot be resumed. " +
                "If the user identifies a task by name instead of id, call `listTasks` first. " +
                "In a group, only tasks from the current chat can be changed."

    const val RESUME_ID = "Numeric id of the scheduled task to resume."

    const val CANCEL_TASK =
        "Cancel a scheduled task by its numeric id (from `listTasks`). " +
                "If the user names a task without giving an id, call `listTasks` first to look it up. " +
                "In a group, only tasks from the current chat can be changed."

    const val CANCEL_ID = "Numeric id of the scheduled task to cancel."
}
