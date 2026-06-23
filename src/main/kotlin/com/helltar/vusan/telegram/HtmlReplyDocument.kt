package com.helltar.vusan.telegram

// the formatting fallback wraps the agent's reply (already Telegram-subset HTML) in a standalone,
// responsive HTML page, so the user still sees the intended structure when Telegram rejects the
// message. it adapts to phone and desktop widths and to the light/dark system theme.
//
// a strict Content-Security-Policy (no scripts, no remote loads) neutralizes any markup the model
// might emit under prompt injection: the file is opened locally by the recipient, so a stray
// `<script>` would otherwise run with a file:// origin. the body is embedded verbatim because the
// CSP — not escaping — is what makes it safe, and the surviving tags are exactly what should render.

private const val REPLY_DOCUMENT_TITLE = "Message"

internal fun htmlReplyDocument(body: String): String =
    """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data: https:">
<title>$REPLY_DOCUMENT_TITLE</title>
<style>
:root { color-scheme: light dark; --bg:#ffffff; --fg:#1c1c1e; --muted:#8a8a8e; --link:#0a7cff; --code-bg:#f2f2f7; --quote:#c7c7cc; }
@media (prefers-color-scheme: dark) { :root { --bg:#1c1c1e; --fg:#e9e9ea; --muted:#9b9b9f; --link:#4aa3ff; --code-bg:#2c2c2e; --quote:#48484a; } }
html { -webkit-text-size-adjust: 100%; }
body { margin:0; background:var(--bg); color:var(--fg); font:16px/1.55 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
main { max-width:720px; margin:0 auto; padding:20px 18px 40px; white-space:pre-wrap; overflow-wrap:break-word; word-break:break-word; }
a { color:var(--link); text-decoration:none; }
a:hover { text-decoration:underline; }
code { font-family:ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size:.92em; background:var(--code-bg); padding:.12em .35em; border-radius:5px; }
pre { white-space:pre; overflow-x:auto; background:var(--code-bg); padding:12px 14px; border-radius:10px; }
pre code { background:none; padding:0; }
blockquote { margin:.4em 0; padding:.15em 0 .15em 14px; border-left:3px solid var(--quote); color:var(--muted); }
tg-spoiler { background:var(--fg); border-radius:4px; color:transparent; }
tg-spoiler:hover { background:transparent; color:inherit; }
</style>
</head>
<body><main>${body.trim()}</main></body>
</html>
"""
