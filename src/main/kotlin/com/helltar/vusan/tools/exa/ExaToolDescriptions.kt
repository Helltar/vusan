package com.helltar.vusan.tools.exa

internal object ExaToolDescriptions {

    const val WEB_SEARCH =
        "Search the web for up-to-date information on any topic, event, person, or question. " +
                "Use it whenever the user asks about recent news, facts, or anything that needs a live check. " +
                "Write the query as a natural-language description of what to find, not as keywords: the search matches meaning. " +
                "Each result comes back with the passages of the page that bear on the query; `extractPageContent` reads one in full."

    const val WEB_SEARCH_QUERY =
        "What to find, as a phrase or question with the names, dates and kind of source that pin it down, e.g. `recent reviews of the Pixel 11 camera`."

    const val WEB_SEARCH_MAX_RESULTS =
        "Number of results to return, from 1 to 10. " +
                "Prefer 3 to 5."

    const val WEB_SEARCH_CATEGORY =
        "Kind of source to search. " +
                "Allowed values: `news` for current events and articles, `publication` for research papers and journal articles, `company` for company pages, `people` for profiles of a person. " +
                "Leave empty to search every kind."

    const val WEB_SEARCH_TIME_RANGE =
        "How recently the results were published. " +
                "Allowed values: `day`, `week`, `month`, `year`. " +
                """Use when the user asks about something time-sensitive ("recent", "today", "this week", "latest"). """ +
                "Leave empty for no time filter; `company` and `people` ignore it."

    const val EXTRACT_PAGE_CONTENT =
        "Fetch and extract the full text content of a web page by URL into your own context. " +
                "This is the default page reader — use it to read a search result in full, to answer or summarize a page the user linked, or to quote it. " +
                "When it fails, comes back empty or cuts a long page short, `readPage` reads the same address directly and continues a long page in parts. " +
                "This tool sends nothing to the user. " +
                "Use `downloadFile` instead when the user asks to download, save, or receive the link itself, and for archives, images, and other files that are not documents."

    const val EXTRACT_PAGE_URL =
        "The full URL of the page to extract content from, e.g. `https://example.com/article`."
}
