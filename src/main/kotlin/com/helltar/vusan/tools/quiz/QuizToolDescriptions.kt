package com.helltar.vusan.tools.quiz

internal object QuizToolDescriptions {

    const val CREATE_QUIZ =
        "Creates a quiz poll with answer options. " +
                "Use when the user asks for a quiz, trivia, test, or riddle with selectable answers in the chat. " +
                "Generate the question and answers yourself if needed, keep options concise, provide exactly one correct answer, and after calling this tool write a short natural comment for the user; the quiz will be sent automatically."

    const val QUESTION =
        "Quiz question shown in the chat. " +
                "Keep it short and clear."

    const val OPTIONS =
        "Answer options for the quiz. " +
                "Provide 2 to 10 items."

    const val CORRECT_OPTION_INDEX =
        "Zero-based index of the correct option inside `options`."

    const val EXPLANATION =
        "Optional short explanation shown after answering."

    const val IS_ANONYMOUS =
        "Whether the chat should hide who voted. " +
                "Default `false` to keep the quiz feel more personal."
}
