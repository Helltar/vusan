package com.helltar.vusan.tools.searxng

internal object SearxngToolDescriptions {

    const val META_SEARCH =
        "Search the web and get back titles, links, and short snippets. " +
                "This is the default tool for any live lookup — recent news, facts, prices, people, products, events, documentation, or anything you are not certain about. " +
                "It queries many engines at once (Google, Bing, DuckDuckGo, Wikipedia and others), so one call is usually enough. " +
                "Use `extractPageContent` afterwards when a result has to be read in full."

    const val META_SEARCH_QUERY =
        "Search query — a concise phrase or question."

    const val META_SEARCH_MAX_RESULTS =
        "Number of results to return, from 1 to 15. " +
                "Prefer 5 to 8."

    const val META_SEARCH_CATEGORIES =
        "Search category that scopes the query. " +
                "Allowed values: `general`, `news`, `it`, `science`, `videos`, `music`, `files`, `social media`, `map`. " +
                "Use `news` for current events and breaking stories, `it` for programming and software questions, `science` for research and papers. " +
                "Defaults to `general`."

    const val META_SEARCH_TIME_RANGE =
        "Time window for results. " +
                "Allowed values: `day`, `week`, `month`, `year`. " +
                """Use when the user asks about something time-sensitive ("recent", "today", "this week", "latest"). """ +
                "Leave empty for no time filter."

    const val META_SEARCH_LANGUAGE =
        "Language of the results, as a code such as `uk`, `en`, `pl`, or a full locale such as `uk-UA`. " +
                "Set it to the language the answer should come from, which is usually the language the user wrote in. " +
                "Leave empty to search every language."

    const val META_SEARCH_IMAGES =
        "Search the web for images and send them to the chat. " +
                "This is the fallback picture search: prefer `searchImages` when it is offered, and use this one when `searchImages` reported that it could not download anything, or when it is not offered at all. " +
                """Use when the user asks to show, send, or find a picture/photo of something — for example "show me a photo of the Eiffel Tower", "send a BMW X6 picture", "find a red panda photo". """ +
                "Multiple results are sent as a Telegram media group. " +
                "Do not use for animated GIFs (use `searchGif` instead). " +
                "This tool does not report what is in each photo, so do not describe their contents as if you had seen them."

    const val META_SEARCH_IMAGES_QUERY =
        "Image search query — a concise descriptive phrase, e.g. `BMW X6`, `Eiffel Tower at night`, `red panda`."

    const val META_SEARCH_IMAGES_MAX_RESULTS =
        "How many images to return, from 1 to 10. " +
                "Prefer 3 to 5. " +
                "When 2 or more images are returned they are sent as a single media group."
}
