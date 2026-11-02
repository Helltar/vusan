package com.helltar.vusan.telegram.tools.chat

internal object ForumTopicToolDescriptions {

    const val CREATE_TOPIC =
        "Opens a new forum topic in this group, and posts a first message in it when `message` is given. " +
                "Use when someone asks for a new topic, or to move a discussion into one of its own. " +
                "It works only if the person asking may manage topics themselves, and the result says so when they may not. " +
                "Your reply to this turn still goes to the topic the conversation is in, so mention the new topic there."

    const val TOPIC_NAME =
        "Required topic name, 1-$MAX_TOPIC_NAME_CHARS characters."

    const val TOPIC_MESSAGE =
        "Optional first message, posted by you in the new topic, in the same formatting as your replies. " +
                "Leave empty to open the topic without one."

    const val TOPIC_COLOR =
        "Optional icon color: `blue`, `yellow`, `violet`, `green`, `rose` or `red`. " +
                "Leave empty to let Telegram choose."

    const val RENAME_TOPIC =
        "Renames a forum topic: the one this conversation is in, or one `createTopic` opened. " +
                "Use only when someone asks to rename a topic. " +
                "It works only if the person asking is an admin who may manage topics."

    const val NEW_TOPIC_NAME =
        "Required new name, 1-$MAX_TOPIC_NAME_CHARS characters."

    const val TOPIC_ID =
        "Optional topic id that `createTopic` returned. " +
                "Leave empty for the topic this conversation is in."

    const val CLOSE_TOPIC =
        "Closes a forum topic, so that only admins can write in it until it is reopened: the one this conversation is in, or one `createTopic` opened. " +
                "Use only when someone asks to close a topic. " +
                "It works only if the person asking is an admin who may manage topics."

    const val REOPEN_TOPIC =
        "Reopens a closed forum topic: the one this conversation is in, or one `createTopic` opened. " +
                "Use only when someone asks to reopen a topic. " +
                "It works only if the person asking is an admin who may manage topics."
}
