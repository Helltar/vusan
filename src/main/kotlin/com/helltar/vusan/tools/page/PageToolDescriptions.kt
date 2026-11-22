package com.helltar.vusan.tools.page

internal object PageToolDescriptions {

    const val READ_PAGE =
        "Reads the web page at an `http` or `https` URL into your own context: its article text, without the navigation, menus and scripts around it. " +
                "This is the fallback page reader: prefer `extractPageContent` when it is offered, and use this one when `extractPageContent` failed, came back empty or cut the page short, or is not offered at all. " +
                "Use it to read a search result in full, to answer or summarize a page the user linked, or to quote it; it sends nothing to the user. " +
                "A plain text file is read as it is; a PDF, an archive, an image or another binary is not readable here — `downloadFile` sends such a link to the user as a file. " +
                "A long page comes back in parts: the result says where it stopped, and `offset` continues from there, so read on only while the answer is still ahead."

    const val URL =
        "The full URL of the page to read, for example `https://example.com/article`."

    const val OFFSET =
        "Character position to continue from, taken from the previous result's `offset=` line; defaults to `0`, the start of the page."

    const val PAGE_IMAGES =
        "Sends the pictures of a web page at an `http` or `https` URL to the chat: the preview the page names for itself, then the photos in its content, without logos, icons and thumbnails. " +
                "Use it when the user links a page and wants its photos, and to take pictures from a page a search found when an image search sent nothing fitting. " +
                "A link straight to an image works too. " +
                "Multiple pictures go as one album. " +
                "A page that loads its pictures with scripts, as Instagram and Pinterest do, gives none here. " +
                "This tool does not report what is in each photo, so do not describe their contents as if you had seen them."

    const val PAGE_IMAGES_URL =
        "The full URL of the page whose pictures to send, for example `https://example.com/article`."

    const val PAGE_IMAGES_MAX_RESULTS =
        "How many pictures to send, from 1 to 10. " +
                "Prefer 3 to 5; the preview alone is often the one photo a news page has."

    const val PAGE_IMAGES_SEND =
        "Whether the pictures go to the chat; `true` by default. " +
                "`false` keeps them for a later call instead, another tool or the sandbox, which takes each by the label the result names."

    const val PAGE_IMAGES_SPOILER =
        "`true` sends the pictures under a spoiler blur that lifts when tapped, for a reveal, a punchline or whatever the user asked to hide."
}
