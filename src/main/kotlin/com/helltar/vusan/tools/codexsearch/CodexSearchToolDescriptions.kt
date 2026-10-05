package com.helltar.vusan.tools.codexsearch

internal object CodexSearchToolDescriptions {

    const val ANSWER_FROM_WEB =
        "Ask a question and get back a short written answer researched on the live web, with the pages it rests on. " +
                "A separate search model runs the queries and reads the pages itself, so one call stands in for a search plus several page reads, and takes ten seconds or more. " +
                "Use it when `webSearch` and `metaSearch` are not offered or have failed, and for a question whose answer has to be pieced together from several pages. " +
                "Prefer `webSearch` for a quick lookup, for a list of links, and whenever the user wants the sources themselves rather than a summary. " +
                "The answer is another model's summary: when a number or a quote matters, read it on one of the listed sources with `readPage` before repeating it."

    const val ANSWER_FROM_WEB_QUESTION =
        "The whole question, in the user's language, with the names, dates and versions that pin it down. " +
                "The search model sees nothing of this conversation, so the question has to stand on its own."
}
