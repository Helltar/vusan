package com.helltar.vusan.tools.memory

internal object MemoryToolDescriptions {

    const val REMEMBER_ABOUT_ME =
        "Saves a durable detail about the current user to long-term memory — separate from the conversation, so it survives clearing the chat history. " +
                "Use for stable, reusable details (the user's name, preferences, recurring context, ongoing projects) when the user asks you to remember something or you learn something clearly worth keeping — not for transient chit-chat. " +
                "This memory follows the user across DMs and groups and is shown to you in the `<user_memory>` block. " +
                "Read that block first: do not save what it already says, and when the detail refines, corrects or contradicts an item there, pass that item's id as `replacesId` instead of adding a second one. " +
                "It holds about 20 items and drops the oldest past that, so keep it for what will still matter in months. " +
                "After saving, briefly confirm to the user."

    const val REMEMBER_ABOUT_ME_DETAIL =
        "The detail to remember, as a short self-contained statement in the user's language, e.g. `User's name is Olena` or `Prefers metric units`. " +
                "At most 500 characters; anything past that is cut off."

    const val REPLACES_ID =
        "The id of the remembered item this detail takes the place of, as shown after `#` in the memory block. " +
                "The old item is removed and the new one saved in one step, so write the detail in full rather than as an addition to the old one. " +
                "Leave it at `0` for a detail that is new."

    const val REMEMBER_ABOUT_GROUP =
        "Saves a durable detail about this group chat to shared long-term memory — visible to everyone in the group (shown to you in the `<group_memory>` block) and kept even after the chat history is cleared. " +
                "Use for details about the group itself or its members as a collective: its topic, conventions, recurring events, who's who. Any member can add or remove these. " +
                "Never store private personal details here — use `rememberAboutMe` for those. Works only in group chats. " +
                "Read `<group_memory>` first: do not save what it already says, and when the detail refines, corrects or contradicts an item there, pass that item's id as `replacesId` instead of adding a second one. " +
                "It holds about 20 items and drops the oldest past that. " +
                "After saving, briefly confirm."

    const val REMEMBER_ABOUT_GROUP_DETAIL =
        "The detail to remember about the group, as a short self-contained statement, e.g. `This group is the team's standup channel`. " +
                "At most 500 characters; anything past that is cut off."

    const val FORGET_MEMORY =
        "Removes a single remembered item by its id. " +
                "Pass the `#id` shown next to the item in the `<user_memory>` or `<group_memory>` block. " +
                "Use when something remembered is outdated or wrong, or the user asks to forget one specific thing. " +
                "You can only remove your own user memory or the current group's memory."

    const val FORGET_MEMORY_ID =
        "The numeric id of the item to remove, as shown after `#` in the memory blocks."

    const val FORGET_EVERYTHING_ABOUT_ME =
        "Wipes ALL durable memory stored about the current user (the `<user_memory>` block). Does not touch group memory or the conversation history. " +
                """Use only when the user explicitly asks to forget everything you know about them personally, e.g. "forget everything about me" or "delete what you know about me". """ +
                "After calling this, briefly confirm."
}
