package com.helltar.vusan.tools.context

internal object ContextToolDescriptions {

    const val CHECK_CONTEXT_BUDGET =
        "Reports how much room this turn has left for tool results, in tokens. " +
                "Every result you receive is charged against one budget for the whole turn; once it runs out, results arrive truncated and then empty, so further calls teach you nothing. " +
                "Call it before a large read — a full transcript, a wide chat-log window, a long file or command output — to decide whether to narrow the range or answer with what you already have. " +
                "It takes no arguments."
}
