package com.helltar.vusan.tools.reaction

internal object ReactionToolDescriptions {

    const val SET_REACTION =
        """Sets a Telegram reaction (an emoji "like") on a message in the current chat. """ +
                "Use this instead of `sendMessage` when a short emotional acknowledgement is more natural than a textual reply (jokes, cute photos, mild surprise, agreement, sympathy) — a reaction does not produce a message in the chat, it only attaches an emoji to the target message. " +
                "Always pass the `emoji` argument — there is no default emoji and the call fails without it. " +
                "Target resolution: by default the reaction goes on the user's own current message. " +
                """Set `targetRepliedMessage` to `true` when the user is replying to someone and wants that earlier message reacted to (e.g. the user replies to someone's joke and writes "react to it"). """ +
                "Pass `messageId` explicitly only when the user gives you a specific numeric id to react to (it overrides `targetRepliedMessage`). " +
                "Use sparingly — do not stack a reaction on top of a substantive textual reply unless the user clearly asks for both. " +
                "Allowed emoji (Telegram free reaction set): " +
                "`👍`, `👎`, `❤`, `🔥`, `🥰`, `👏`, `😁`, `🤔`, `🤯`, `😱`, `🤬`, `😢`, `🎉`, `🤩`, `🤮`, `💩`, `🙏`, `👌`, `🕊`, " +
                "`🤡`, `🥱`, `🥴`, `😍`, `🐳`, `❤‍🔥`, `🌚`, `🌭`, `💯`, `🤣`, `⚡`, `🍌`, `🏆`, `💔`, `🤨`, `😐`, `🍓`, `🍾`, `💋`, " +
                "`🖕`, `😈`, `😴`, `😭`, `🤓`, `👻`, `👨‍💻`, `👀`, `🎃`, `🙈`, `😇`, `😨`, `🤝`, `✍`, `🤗`, `🫡`, `🎅`, `🎄`, `☃`, " +
                "`💅`, `🤪`, `🗿`, `🆒`, `💘`, `🙉`, `🦄`, `😘`, `💊`, `🙊`, `😎`, `👾`, `🤷‍♂`, `🤷`, `🤷‍♀`, `😡`. " +
                "Other emoji will be rejected by Telegram."

    const val EMOJI =
        "Required. " +
                "Exactly one emoji from the allowed Telegram reaction set. " +
                "Pass the raw emoji character, not its name or shortcode. " +
                "Never omit this argument — the tool fails if `emoji` is missing."

    const val TARGET_REPLIED_MESSAGE =
        "Set to `true` when the user is replying to someone else's message and the reaction should land on that earlier replied-to message, not on the user's current message. " +
                "Default `false` — the reaction lands on the user's own current message. " +
                "Ignored when `messageId` is supplied. " +
                "Fails if there is no `<reply_context>` in the current turn, so do not set it when the user is not replying to anything."

    const val MESSAGE_ID =
        "Optional explicit Telegram message id to react to. " +
                "Pass a positive integer only when the user explicitly tells you which message to react to. " +
                "Overrides `targetRepliedMessage` and the default user-message target."
}
