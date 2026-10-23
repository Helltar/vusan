package com.helltar.vusan.tools.diary

internal object DiaryToolDescriptions {

    const val READ_DIARY =
        "Reads your own diary about this group: an entry for each day worth one, and entries looking back over each week and month, kept long after the chat log is gone. " +
                """Use it when the conversation reaches back past the few days your `<diary>` block shows: "remember when…", "what were we on about last month", "when did we last talk about NAME", what a given month was like. """ +
                "For what exactly was said in the last few weeks, `readGroupLog` is the record and the diary only how you remembered it. " +
                "Only a group has a diary."

    const val READ_DIARY_FROM =
        "First day to read, as `yyyy-MM-dd`, or a whole month as `yyyy-MM`. " +
                "Omit to start from the first entry."

    const val READ_DIARY_TO =
        "Last day to read, included, as `yyyy-MM-dd` or `yyyy-MM`. " +
                "Omit to read just the day or month `from` names; omit both to read the whole diary, as much of it as fits, newest kept."

    const val READ_DIARY_CONTAINS =
        "Only entries that mention this text, whatever its case: a name, a nickname, a word for the topic. " +
                "Entries retell rather than quote, so a name or a single word finds more than a phrase."
}
