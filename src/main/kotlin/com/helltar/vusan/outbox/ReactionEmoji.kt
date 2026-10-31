package com.helltar.vusan.outbox

// the free reaction emoji telegram accepts from bots. its canonical forms lack VS-16 (U+FE0F), and incoming
// emoji are normalized the same way, so `❤️` and `❤` are one reaction.
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
