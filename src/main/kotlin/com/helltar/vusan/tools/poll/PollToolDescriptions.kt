package com.helltar.vusan.tools.poll

internal object PollToolDescriptions {

    const val CREATE_POLL =
        "Creates a Telegram poll (regular vote, no correct answer) with answer options. " +
                "Use when the user asks for a poll, vote, or wants to ask people to choose between options in Telegram. " +
                "If you are unsure between this and a quiz: use `createPoll` for opinions and votes (no right answer), use `createQuiz` for trivia/tests with one correct answer. " +
                "Generate the question and options yourself if needed, keep options concise, " +
                "and after calling this tool write a short natural comment for the user; the poll will be sent automatically."

    const val QUESTION =
        "Poll question shown in Telegram. Keep it short and clear."

    const val OPTIONS =
        "Answer options for the poll. Provide 2 to 10 items."

    const val IS_ANONYMOUS =
        "Whether Telegram should hide who voted. Default `true`, matching Telegram's normal poll behavior. " +
                "Set to `false` only when the user explicitly asks for a public/non-anonymous poll."

    const val ALLOWS_MULTIPLE_ANSWERS =
        "Whether voters can pick more than one option. Default `false`. " +
                "Set to `true` when the user asks for multi-choice or \"choose all that apply\" voting."
}
