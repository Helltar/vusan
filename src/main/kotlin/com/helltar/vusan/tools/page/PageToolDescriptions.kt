package com.helltar.vusan.tools.page

internal object PageToolDescriptions {

    const val READ_PAGE =
        "Reads the web page at an `http` or `https` URL into your own context: its article text, without the navigation, menus and scripts around it. " +
                "Use it to read a search result in full, to answer or summarize a page the user linked, or to quote it; it sends nothing to the user. " +
                "A plain text file is read as it is; a PDF, an archive, an image or another binary is not readable here — `downloadFile` sends such a link to the user as a file. " +
                "A long page comes back in parts: the result says where it stopped, and `offset` continues from there, so read on only while the answer is still ahead. " +
                "When it finds no readable text and `extractPageContent` is offered, try that one: it renders the page before reading it."

    const val URL =
        "The full URL of the page to read, for example `https://example.com/article`."

    const val OFFSET =
        "Character position to continue from, taken from the previous result's `offset=` line; defaults to `0`, the start of the page."
}
