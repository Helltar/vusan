package com.helltar.vusan.telegram.tools.widget

internal object WidgetToolDescriptions {

    const val WIDGET_GUIDE =
        "Returns the rules for writing a Telegram widget: size, layout, colors, what HTML, CSS and JavaScript the clients run, input, performance, the `TelegramWidget` API, state and presses. " +
                "Read it once in this conversation before writing your first widget, and again whenever a widget came out wrong."

    const val SEND_WIDGET =
        "Sends a Telegram widget: a small HTML page with its own CSS and JavaScript, drawn and run inside the message. " +
                "Use it when something works better live than as text or a picture: a chart, a calculator, a timer or countdown, a card to flip through, a game, a poll or sign-up that counts itself, a board you and the user play on. " +
                "Do not use it for plain answers, long text or anything a normal message carries as well. " +
                "Read `widgetGuide` first: widgets have no network, no libraries and no form inputs, and clients silently drop what the guide rules out. " +
                "Telegram takes widgets in private chats only so far: in a group, call `replyInPrivateMessages` first and the widget goes to the person's private chat. " +
                "The widget is sent with your reply; its message id appears in `listWidgets` once it is in the chat."

    const val HTML =
        "The whole widget: one HTML document or fragment with inline `<style>` and `<script>`, at most $MAX_WIDGET_HTML_BYTES bytes and $MAX_WIDGET_COMPRESSED_BYTES bytes after zlib level 9; a larger one is refused with its sizes. " +
                "Also takes a label (`#3`) or `sandbox:<path>` naming the HTML, which then arrives as written."

    const val CAPTION =
        "Required plain text shown under the widget and in the chat list, and all that a client without widgets shows, so say what the widget is in a few words. " +
                "No HTML."

    const val REQUESTS =
        "What a tap that calls `TelegramWidget.request` does: `none` (the default: the widget never asks), `tally` (the bot counts each person's latest choice and publishes the counts, no turn of yours) or `agent` (you get a turn with `<widget_request>` and answer with `setWidgetState`). " +
                "The guide says how a widget uses each."

    const val SHARED =
        "Whether the widget is shared: one shared state for every message showing it, so `sendWidgetAgain` can post it again later with the same counts or score. " +
                "`false` by default."

    const val CHAT_DATA =
        "Optional first chat state, the widget's `chatData`: text of at most $MAX_WIDGET_STATE_BYTES bytes, usually JSON, seen in this message only."

    const val PUBLIC_DATA =
        "Optional first shared state, the widget's `publicData`: text of at most $MAX_WIDGET_STATE_BYTES bytes, usually JSON, seen wherever the widget is shown. " +
                "A `tally` widget starts with empty counts when this is left empty."

    const val WIDTH =
        "Optional width in CSS pixels, from 200 up to the bubble's maximum; `0`, the default, takes the bubble's width."

    const val HEIGHT =
        "Optional height in CSS pixels; `0`, the default, follows the content up to 600."

    const val SET_WIDGET_STATE =
        "Changes what one of your widgets in this chat shows, silently, without a new message: its chat state, its shared state, or both. " +
                "Use it to answer a `<widget_request>`, or when the conversation changes what a widget should show. " +
                "The widget gets the new state as `chatDataChanged` or `publicDataChanged` and redraws; a field you leave out stays as it is, and an empty string clears it."

    const val STATE_MESSAGE_ID =
        "Required message id of the widget, from `<widget_request>` or `listWidgets`."

    const val NEW_CHAT_DATA =
        "Optional new chat state, at most $MAX_WIDGET_STATE_BYTES bytes; leave it out to keep the current one, or pass an empty string to clear it."

    const val NEW_PUBLIC_DATA =
        "Optional new shared state, at most $MAX_WIDGET_STATE_BYTES bytes; leave it out to keep the current one, or pass an empty string to clear it. " +
                "A `tally` widget's shared state is its counts, which only the presses change. " +
                "For a shared widget it changes in every message showing it."

    const val REFRESH_SECONDS =
        "Optional, for a shared widget whose code calls `watchPublicData`: how often clients showing it check for a new shared state, from $MIN_WIDGET_REFRESH_SECONDS to $MAX_WIDGET_REFRESH_SECONDS seconds; telegram's default is 30."

    const val SEND_WIDGET_AGAIN =
        "Posts one of your shared widgets in this chat again, as a new message showing the same widget and the same shared state, with a chat state of its own. " +
                "Use it to bring a poll, a leaderboard or a live card back to the bottom of the chat; an unshared widget cannot be posted again, so send a new one instead."

    const val AGAIN_MESSAGE_ID =
        "Required message id of the shared widget, from `listWidgets`."

    const val AGAIN_CAPTION =
        "Optional plain-text caption for the new message; empty keeps the widget's own."

    const val AGAIN_CHAT_DATA =
        "Optional chat state for the new message, at most $MAX_WIDGET_STATE_BYTES bytes."

    const val LIST_WIDGETS =
        "Lists your latest widgets in this chat, newest first: message id, caption, how presses are handled, whether it is shared, and the state it shows now. " +
                "Use it to find the widget the user means before changing or reposting it."
}
