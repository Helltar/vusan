package com.helltar.vusan.tools.tasks

internal object FollowUpToolDescriptions {

    const val SCHEDULE_FOLLOW_UP =
        "Come back to this person later, on your own initiative, with one message about something they are going through. " +
                """Use it whenever they mention an event with a natural afterwards — an exam tomorrow, an interview on Friday, a flight, a deadline, a doctor's visit, being ill, a first day at a new job — and asking how it went is what an attentive friend would do. """ +
                "One follow-up per event, and not for trivia; when the user asks to be reminded of something, that is `scheduleTask` instead. " +
                "At the chosen time the `prompt` is replayed to you as a new turn, so it must carry the context you will need then. " +
                "You may mention in passing that you will check back, without describing it as a scheduled action."

    const val PROMPT =
        "Imperative describing what to do when it fires, in the user's language, carrying the context that makes it make sense. " +
                """Write `ask how the interview at the bank went, they were nervous about the technical part`, not `remind about interview`. """ +
                "It runs as an ordinary turn, so tools are available to you then."

    const val AT =
        "When to come back, as an ISO local datetime like `2026-05-31T10:00`. " +
                "Resolve it against `<current_time>` in the current turn. " +
                "Pick a considerate moment — the morning after the exam, the evening of the interview, not the middle of the night, and not so soon that nothing has happened yet."

    const val TIMEZONE =
        "IANA timezone name like `Europe/Kyiv`. " +
                "Pass it only when you actually know where the user is. " +
                "Otherwise omit — the bot's default is used."

    const val TITLE =
        "Short human label shown in the user's task list, e.g. `check in about the exam`. " +
                "Keep it recognizable to the user, since they can see and cancel it."
}
