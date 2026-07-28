package com.helltar.vusan.tools.choice

internal object InlineChoiceToolDescriptions {

    const val ASK_WITH_BUTTONS =
        "Asks the current user one concise question with inline answer buttons. " +
                "Use when you need the user to choose among concrete alternatives before you can continue, including a confirmation such as yes/no. " +
                "Do not use for rhetorical questions, open-ended input, decorative navigation, or group voting; use `createPoll` for a vote. " +
                "Do not ask when you can safely answer directly or make a harmless assumption. " +
                "After calling this tool, do not repeat the question with `sendMessage` and do not perform the action that depends on the answer. " +
                "End the turn and wait: the selected option will arrive as the user's next turn."

    const val QUESTION =
        "The question shown above the buttons, in the user's language. " +
                "Use plain text without HTML or Markdown and keep it concise."

    const val OPTIONS =
        "Two to ten short, distinct answer labels in the user's language. " +
                "Each label must make sense as the user's direct answer to the question."
}
