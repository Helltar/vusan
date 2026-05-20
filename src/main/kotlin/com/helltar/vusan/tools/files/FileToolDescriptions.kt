package com.helltar.vusan.tools.files

internal object FileToolDescriptions {

    const val SEND_FILE =
        "Sends arbitrary text content to the user as a downloadable file (Telegram document). " +
                "Use when the user asks to save, download, export, or receive text as a file, " +
                "for example an article as markdown, notes as txt, or code as a file. " +
                "You are responsible for formatting `content` exactly how the file should look. " +
                "After calling this tool, write a short natural comment for the user; the file will be sent automatically."

    const val CONTENT =
        "Full text content of the file, already formatted (e.g. markdown body, plain text, CSV, JSON, code)."

    const val FILENAME =
        "Desired file name including extension, for example `article.md`, `notes.txt`, or `data.csv`. " +
                "Pick a short, descriptive name based on the content."
}
