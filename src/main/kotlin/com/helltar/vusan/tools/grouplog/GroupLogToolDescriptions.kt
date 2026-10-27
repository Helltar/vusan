package com.helltar.vusan.tools.grouplog

internal object GroupLogToolDescriptions {

    const val READ_GROUP_LOG =
        "Reads what was actually said in this group, including the messages nobody addressed to you. " +
                "Use it whenever the answer depends on the chat's own past: recaps and TL;DRs, " +
                """"what did I miss", "what was decided", "what did NAME write earlier", "who shared that link", "was NAME in the chat". """ +
                "Media is recorded as a note of what arrived, not its content, and forwards name the channel they came from. " +
                "To find whether, when or by whom something was mentioned, pass `contains`: it searches every message of the window, so the window may be as wide as the log. " +
                "Without it, ask for the narrowest window that can hold the answer — a wide one gives its past days as your diary entries instead of quoting them. " +
                "The result header carries the exact message count for the window, so answer how many and how often from it and never by counting the quoted lines. " +
                "Use these messages as your evidence and answer from them; do not paste the transcript back to the user."

    const val READ_GROUP_LOG_WINDOW =
        "How far back to read, as a duration ending now: `30m`, `2h`, `24h`, `7d`. " +
                "The log is kept for about a month; a window past that is answered with the exact limit."

    const val READ_GROUP_LOG_AUTHOR =
        "Restrict to one person, by username (with or without `@`) or by the name they display. " +
                "Omit for the whole conversation. " +
                "The header count then covers only that person, and stays exact even when their messages are too many to quote."

    const val READ_GROUP_LOG_CONTAINS =
        "Only messages that mention this text, whatever its case: a name, a word for the topic, part of a link or a file name. " +
                "It matches the text as written and not the other forms of a word, so a stem or a single word finds more than a phrase. " +
                "The header count then covers only the matches, which are quoted and never summarized."

    const val CLEAR_GROUP_LOG =
        "Deletes this group's recorded messages and your diary about the group. " +
                "Use only when someone explicitly asks to wipe what the group said, " +
                """"forget this chat", "delete the log", "clear the group history". """ +
                "It affects everyone in the group, not just the person asking, so confirm before calling it. " +
                "Personal conversation history and durable memory are untouched."
}
