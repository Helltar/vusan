package com.helltar.vusan.tools.message

internal object MessageToolDescriptions {

    const val SEND_MESSAGE =
        "Sends a Telegram text message to the user. " +
                "Use this for any substantive text content the user must see: web search summaries, news digests, riddle questions, facts, answers, explanations, lists. " +
                "Call it once per distinct message; calls are sent in order alongside any other queued media. " +
                "Keep a reply to a few messages; never split one answer into many tiny separate messages. " +
                "Do NOT paste raw tool payloads here; write in the user's language, concise, natural. " +
                "Markdown is allowed but keep it light."

    const val TEXT =
        "Full text of the message to send to the user. " +
                "Must be non-empty."

    const val SEND_RICH_MESSAGE =
        "Sends a large, structured reply as a Telegram rich message (Bot API 10.1). " +
                "Use this ONLY when the content is genuinely large and structured: long multi-section explanations, comparison tables, detailed step-by-step guides, or documents with headings. " +
                "For everything else — normal answers, short replies, a little bold or a simple list — use `sendMessage`, which renders on every client. " +
                "Some third-party clients (e.g. Telegram X) show rich messages as unsupported, so reserve this for cases where the structure is clearly worth it. " +
                "After calling it, do not resend the same content with `sendMessage`."

    const val RICH_MARKDOWN =
        "Message body in GitHub-Flavored Markdown: " +
                "`#` to `######` headings, `**bold**`, `*italic*`, `~~strikethrough~~`, `` `code` ``, `||spoiler||`, `[text](url)` links, " +
                "`-` / `1.` lists, `- [ ]` / `- [x]` task items, `>` quotes, `---` divider, fenced code blocks, and `| a | b |` tables with a `| --- | --- |` separator row. " +
                "Must be non-empty; up to 32768 characters."

    const val REPLY_IN_PRIVATE_MESSAGES =
        "Switches the reply target so all subsequent queued messages and media are sent to the user's private chat with the bot instead of the current chat. " +
                "Use when the user explicitly asks for something to be sent in DMs, privately, or in personal messages, especially from a group. " +
                "Once switched, the change applies to every tool you call afterwards in this turn. " +
                "Note: the user must have started a private chat with the bot at least once by sending `/start`; otherwise the private send will fail and a notice will appear in the original chat."
}
