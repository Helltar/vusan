package com.helltar.vusan.tools.files

internal object FileToolDescriptions {

    const val SEND_FILE =
        "Sends arbitrary text content to the user as a downloadable file (a document). " +
                "Use when the user asks to save, download, export, or receive text as a file, for example an article as markdown, notes as txt, or code as a file. " +
                "You are responsible for formatting `content` exactly how the file should look. " +
                "After calling this tool, write a short natural comment for the user; the file will be sent automatically."

    const val CONTENT =
        "Full text content of the file, already formatted (e.g. markdown body, plain text, CSV, JSON, code). " +
                "A label (`#4`) or `sandbox:<path>` instead sends the whole text it names."

    const val FILENAME =
        "Desired file name including extension, for example `article.md`, `notes.txt`, or `data.csv`. " +
                "Pick a short, descriptive name based on the content."

    const val DOWNLOAD_FILE =
        "Downloads whatever is at an `http`/`https` URL and sends it to the user as a document. " +
                "Use when the user asks to download, save, or fetch a link, for example a PDF, an archive, a document, an image, or a whole web page saved as a file. " +
                "Images arrive as uncompressed documents, not as photos. " +
                "Uploads are capped at $MAX_DOWNLOAD_MB MB by the chat; a larger file is reported back to you instead of being sent. " +
                "Call this only when the user wants the file itself in the chat; to read a page so you can answer or summarize it, use `extractPageContent` instead, or `readPage` when that one is not offered. " +
                "Use the YouTube tools for YouTube links. " +
                "After calling this tool, write a short natural comment for the user; the document will be sent automatically."

    const val DOWNLOAD_URL =
        "Direct `http` or `https` URL of the file or page to download. " +
                "Pass the address of the file itself, not a search or preview page that merely links to it."

    const val DOWNLOAD_FILENAME =
        "Optional file name including extension, for example `report.pdf` or `page.html`. " +
                "Leave empty to keep the name the server reports or the one in the URL."

    const val DOWNLOAD_SEND =
        "Whether the file goes to the chat; `true` by default. " +
                "`false` keeps it for a later call instead, another tool or the sandbox, which takes it by the label the result names."
}
