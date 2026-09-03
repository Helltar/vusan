package com.helltar.vusan.tools.files

internal object FileToolDescriptions {

    const val SEND_FILE =
        "Sends arbitrary text content to the user as a downloadable file (Telegram document). " +
                "Use when the user asks to save, download, export, or receive text as a file, for example an article as markdown, notes as txt, or code as a file. " +
                "You are responsible for formatting `content` exactly how the file should look. " +
                "After calling this tool, write a short natural comment for the user; the file will be sent automatically."

    const val CONTENT =
        "Full text content of the file, already formatted (e.g. markdown body, plain text, CSV, JSON, code)."

    const val FILENAME =
        "Desired file name including extension, for example `article.md`, `notes.txt`, or `data.csv`. " +
                "Pick a short, descriptive name based on the content."

    const val DOWNLOAD_FILE =
        "Downloads whatever is at an `http`/`https` URL and sends it to the user as a Telegram document. " +
                "Use when the user asks to download, save, or fetch a link, for example a PDF, an archive, a document, an image, or a whole web page saved as a file. " +
                "Images arrive as uncompressed documents, not as photos. " +
                "Uploads are capped at $MAX_DOWNLOAD_MB MB by Telegram; a larger file is reported back to you instead of being sent. " +
                "Call this only when the user wants the file itself in the chat; to read a page so you can answer or summarize it, use a page content extraction tool instead. " +
                "Use the YouTube tools for YouTube links. " +
                "After calling this tool, write a short natural comment for the user; the document will be sent automatically."

    const val DOWNLOAD_URL =
        "Direct `http` or `https` URL of the file or page to download. " +
                "Pass the address of the file itself, not a search or preview page that merely links to it."

    const val DOWNLOAD_FILENAME =
        "Optional file name including extension, for example `report.pdf` or `page.html`. " +
                "Leave empty to keep the name the server reports or the one in the URL."

    const val SEND_CHAT_FILE =
        "Sends a file that is already in this chat back into it as a Telegram document, named by the `file_id` from the message metadata. " +
                "Use when the user asks to download, save, or get the file behind a sticker, photo, GIF, video, voice message, audio track, or document, for example `download this sticker` in a reply to one. " +
                "The stored bytes are sent unchanged, so a sticker arrives as `.webp` (`.tgs` or `.webm` when it is animated) and a photo as the `.jpg` Telegram kept. " +
                "Telegram serves bots files of at most $MAX_TELEGRAM_FILE_MB MB; a larger one is reported back to you instead of being sent. " +
                "Use `downloadFile` for an `http` or `https` link, and `sendFile` for text you wrote yourself. " +
                "After calling this tool, write a short natural comment for the user; the document will be sent automatically."

    const val CHAT_FILE_ID =
        "Required `file_id`, copied exactly from the `file_id` metadata line of the message that carries the file. " +
                "A `file_unique_id` cannot be downloaded, and a guessed or remembered id never works."

    const val CHAT_FILENAME =
        "Optional file name including extension, for example `sticker.webp` or `photo.jpg`. " +
                "Leave empty to keep the name Telegram reports; a name given without an extension takes Telegram's own."
}
