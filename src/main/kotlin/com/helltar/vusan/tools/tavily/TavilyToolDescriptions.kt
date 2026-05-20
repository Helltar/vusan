package com.helltar.vusan.tools.tavily

internal object TavilyToolDescriptions {

    const val WEB_SEARCH =
        "Search the web for up-to-date information on any topic, event, person, or question. " +
                "Use when the user asks about recent news, facts, or anything that requires a live web lookup."

    const val WEB_SEARCH_QUERY =
        "Search query — a concise phrase or question."

    const val WEB_SEARCH_MAX_RESULTS =
        "Number of results to return, from 1 to 10. Prefer 3 to 5."

    const val WEB_SEARCH_TOPIC =
        "Search category. Use `general` for default web search, `news` for current events / breaking stories / recent articles, " +
                "`finance` for stock prices, markets, crypto, economic data. Defaults to `general`."

    const val WEB_SEARCH_TIME_RANGE =
        "Time window for results. Allowed values: `day`, `week`, `month`, `year`. " +
                "Use when the user asks about something time-sensitive (`recent`, `today`, `this week`, `latest`). Leave empty for no time filter."

    const val SEARCH_IMAGES =
        "Search the web for images and send them to the chat. " +
                "Use when the user asks to show, send, or find a picture/photo of something — for example " +
                """"show me a photo of the Eiffel Tower", "send a BMW X6 picture", "find a red panda photo". """ +
                "Use this for static images, including anime art or character pictures. " +
                "Multiple results are sent as a Telegram media group. Do not use for animated GIFs (use `searchGif` instead). " +
                "The tool returns a description of what is visible in each photo. " +
                "If the user asked you to describe / say what's on the photo (`describe it`, `what's on it`, `what does it show`), use these descriptions as the source — " +
                "rewrite them in the user's language as your plain reply (becomes the caption for a single image) or via `sendMessage` (after a media group)."

    const val SEARCH_IMAGES_QUERY =
        "Image search query — a concise descriptive phrase, e.g. `BMW X6`, `Eiffel Tower at night`, `red panda`."

    const val SEARCH_IMAGES_MAX_RESULTS =
        "How many images to return, from 1 to 10. Prefer 3 to 5. " +
                "When 2 or more images are returned they are sent as a single media group."

    const val EXTRACT_PAGE_CONTENT =
        "Fetch and extract the full text content of a web page by URL. " +
                "Use when the user provides a link or wants a detailed summary of a specific article, page, or document."

    const val EXTRACT_PAGE_URL =
        "The full URL of the page to extract content from, e.g. `https://example.com/article`."
}
