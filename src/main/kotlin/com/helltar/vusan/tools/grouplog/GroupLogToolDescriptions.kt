package com.helltar.vusan.tools.grouplog

internal object GroupLogToolDescriptions {

    const val READ_GROUP_LOG =
        "Reads what was actually said in this group, including the messages nobody addressed to you. " +
                "Use it whenever the answer depends on the chat's own past: recaps and TL;DRs, " +
                """"what did I miss", "what was decided", "what did NAME write earlier", "who shared that link". """ +
                "Media is recorded as a note of what arrived, not its content, and forwards name the channel they came from. " +
                "Ask for the narrowest window that can hold the answer — a wide one is summarized per day instead of quoted. " +
                "The result header carries the exact message count for the window, so answer how many and how often from it and never by counting the quoted lines. " +
                "Use these messages as your evidence and answer from them; do not paste the transcript back to the user."

    const val READ_GROUP_LOG_WINDOW =
        "How far back to read, as a duration ending now: `30m`, `2h`, `24h`, `7d`. " +
                "Reaches back at most `90d`."

    const val READ_GROUP_LOG_AUTHOR =
        "Restrict to one person, by Telegram username (with or without `@`) or by the name they display. " +
                "Omit for the whole conversation. " +
                "The header count then covers only that person, and stays exact even when their messages are too many to quote."

    const val CLEAR_GROUP_LOG =
        "Deletes this group's recorded messages and every cached daily recap. " +
                "Use only when someone explicitly asks to wipe what the group said, " +
                """"forget this chat", "delete the log", "clear the group history". """ +
                "It affects everyone in the group, not just the person asking, so confirm before calling it. " +
                "Personal conversation history and durable memory are untouched."
}
