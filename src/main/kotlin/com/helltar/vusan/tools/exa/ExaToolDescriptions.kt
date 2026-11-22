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

    const val SEARCH_IMAGES =
        "Search the web for images and send them to the chat. " +
                """Use when the user asks to show, send, or find a picture/photo of something — for example "show me a photo of the Eiffel Tower", "send a BMW X6 picture", "find a red panda photo". """ +
                "The pictures come from the pages found for the query, so name what the photo should show rather than a topic. " +
                "Multiple results are sent as one album. " +
                "Each call sends its own album, so a second call gives the user a second album rather than a longer one. " +
                "Do not use for animated GIFs (use `searchGifs` instead). " +
                "This tool does not report what is in each photo, so do not describe their contents as if you had seen them."

    const val SEARCH_IMAGES_QUERY =
        "What the picture shows, as a descriptive phrase, e.g. `BMW X6 front view`, `Eiffel Tower at night`, `red panda in a tree`."

    const val SEARCH_IMAGES_MAX_RESULTS =
        "How many images to send, from 1 to 10. " +
                "Prefer 3 to 5. " +
                "When 2 or more images are sent they go as a single media group."

    const val SEARCH_IMAGES_SEND =
        "Whether the images go to the chat; `true` by default. " +
                "`false` keeps them for a later call instead, another tool or the sandbox, which takes each by the label the result names."

    const val SEARCH_IMAGES_SPOILER =
        "`true` sends the images under a spoiler blur that lifts when tapped, for a reveal, a punchline or whatever the user asked to hide."

    const val EXTRACT_PAGE_CONTENT =
        "Fetch and extract the full text content of a web page by URL into your own context. " +
                "This is the default page reader — use it to read a search result in full, to answer or summarize a page the user linked, or to quote it. " +
                "When it fails, comes back empty or cuts a long page short, `readPage` reads the same address directly and continues a long page in parts. " +
                "This tool sends nothing to the user. " +
                "Use `downloadFile` instead when the user asks to download, save, or receive the link itself, and for archives, images, and other files that are not documents."

    const val EXTRACT_PAGE_URL =
        "The full URL of the page to extract content from, e.g. `https://example.com/article`."
}
