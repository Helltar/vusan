package com.helltar.vusan.tools.codexsearch

internal object CodexSearchToolDescriptions {

    const val ANSWER_FROM_WEB =
        "Ask a question and get back a short written answer researched on the live web, with the pages it rests on. " +
                "A separate search model runs the queries and reads the pages itself, so one call stands in for a search plus several page reads, and takes ten seconds or more. " +
                "Use it whenever the user asks about recent news, facts, or anything that needs a live check, and for a question whose answer has to be pieced together from several pages. " +
                "It gives no list of results to pick from: when the user wants the sources themselves, give them the pages it names. " +
                "The answer is another model's summary: when a number or a quote matters, read it on one of the listed sources with `readPage` before repeating it."

    const val ANSWER_FROM_WEB_QUESTION =
        "The whole question, in the user's language, with the names, dates and versions that pin it down. " +
                "The search model sees nothing of this conversation, so the question has to stand on its own."
}
