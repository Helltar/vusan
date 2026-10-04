package com.helltar.vusan.outbox

// free reaction emoji accepted by Telegram for bot-set reactions. telegram's
// canonical forms are without VS-16 (U+FE0F); we normalize incoming emoji the
// same way before lookup so `❤️` and `❤` are treated as the same reaction.
private const val VARIATION_SELECTOR_16 = '️'

internal val ALLOWED_REACTION_EMOJI: Set<String> =
    setOf(
        "👍", "👎", "❤", "🔥", "🥰", "👏", "😁", "🤔", "🤯", "😱",
        "🤬", "😢", "🎉", "🤩", "🤮", "💩", "🙏", "👌", "🕊", "🤡",
        "🥱", "🥴", "😍", "🐳", "❤‍🔥", "🌚", "🌭", "💯", "🤣", "⚡",
        "🍌", "🏆", "💔", "🤨", "😐", "🍓", "🍾", "💋", "🖕", "😈",
        "😴", "😭", "🤓", "👻", "👨‍💻", "👀", "🎃", "🙈", "😇", "😨",
        "🤝", "✍", "🤗", "🫡", "🎅", "🎄", "☃", "💅", "🤪", "🗿",
        "🆒", "💘", "🙉", "🦄", "😘", "💊", "🙊", "😎", "👾",
        "🤷‍♂", "🤷", "🤷‍♀", "😡",
    )

internal fun normalizeReactionEmoji(raw: String): String =
    raw.filterNot { it == VARIATION_SELECTOR_16 }
