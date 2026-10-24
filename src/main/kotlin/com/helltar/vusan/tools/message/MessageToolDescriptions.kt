package com.helltar.vusan.tools.message

internal object MessageToolDescriptions {

    const val SEND_MESSAGE =
        "Sends a text message to the user, for an answer with substance: facts, explanations, search results, news digests, lists. " +
                "Messages go out in call order with any other queued media. " +
                "One answer is one message, or a few for a long one. " +
                "Format it as Telegram HTML with the listed tags; Markdown is for `sendRichMessage` alone."

    const val TEXT =
        "Full text of the message to send to the user. " +
                "Must be non-empty. " +
                "A label (`#4`) or `sandbox:<path>` instead sends the whole text it names, such as a report written in the sandbox."

    const val SEND_RICH_MESSAGE =
        "Sends a large, structured reply as a rich message. " +
                "Use this ONLY when the content is genuinely large and structured: long multi-section explanations, comparison tables, detailed step-by-step guides, or documents with headings. " +
                "For everything else — normal answers, short replies, a little bold or a simple list — use `sendMessage`, which renders on every client. " +
                "Some third-party clients show rich messages as unsupported, so reserve this for cases where the structure is clearly worth it. " +
                "After calling it, do not resend the same content with `sendMessage`."

    const val RICH_MARKDOWN =
        "Message body in GitHub-Flavored Markdown: " +
                "`#` to `######` headings, `**bold**`, `*italic*`, `~~strikethrough~~`, `` `code` ``, `||spoiler||`, `[text](url)` links, " +
                "`-` / `1.` lists, `- [ ]` / `- [x]` task items, `>` quotes, `---` divider, fenced code blocks, and `| a | b |` tables with a `| --- | --- |` separator row. " +
                "Must be non-empty; up to 32768 characters. " +
                "A label (`#4`) or `sandbox:<path>` instead sends the whole text it names, such as a report written in the sandbox."

    const val ANNOUNCE_PLAN =
        "Tells the user what you are about to do, before you start doing it. " +
                "Everything else you send reaches the chat only when your whole turn is over, so on work that will take a while — a sandbox build, a long download, a series of searches — this is the only way to say anything while it is still worth saying. " +
                "Call it once, before the first slow tool call, and only when the work ahead is genuinely long: a quick answer needs no announcement. " +
                "One or two sentences in the user's language, saying what you are going to make, not how you will do it. " +
                "Format with the listed HTML tags only (`<b>`, `<i>`, `<code>`, ...), never Markdown. " +
                "The user reads it while you work, so do not repeat it in your final answer."

    const val PLAN_TEXT =
        "What you are about to do, in one or two sentences. " +
                "Must be non-empty."

    const val SEND_MESSAGE_NOW =
        "Sends a message to the user immediately, while your turn keeps running, and it stays in the chat. " +
                "Use it during long work for something worth reading before the rest is done: the address of a first working version, a finding that changes what you will do next, one step finished in a job of several. " +
                "A few per turn at most; everything else waits for the end, and the final answer still goes through `sendMessage` or a plain reply, without repeating what was sent this way. " +
                "Format with the listed HTML tags only, never Markdown."

    const val NOW_TEXT =
        "The message to send now, in the user's language. " +
                "Must be non-empty."

    const val REPLY_IN_PRIVATE_MESSAGES =
        "Switches the reply target so all subsequent queued messages and media are sent to the user's private chat with the bot instead of the current chat. " +
                "Use when the user explicitly asks for something to be sent in DMs, privately, or in personal messages, especially from a group. " +
                "Once switched, the change applies to every tool you call afterward in this turn. " +
                "Note: the user must have started a private chat with the bot at least once by sending `/start`; otherwise the private send will fail and a notice will appear in the original chat."

    const val STAY_SILENT =
        "Ends your turn without answering, on purpose: nothing is sent, not even a reaction. " +
                "It is for a message that asks you to be quiet or not to write to its author, and for an insult you choose not to dignify. " +
                "A question you would rather skip, something you cannot do or doubt gets an answer instead, or a word on what is in the way. " +
                "Call it before anything is sent: a call that only records something, such as `leaveAlone`, may come first, and once a message, a reaction or an announcement is on its way it is refused."

    const val SILENCE_REASON =
        "A few words for the operator's log saying what made you stay silent. " +
                "The chat never sees it."
}
