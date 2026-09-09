package com.helltar.vusan.request

/**
 * A messenger vusan is reachable on.
 *
 * The name is stored as-is in every table that holds owned state, so it is part of the schema:
 * renaming one orphans every row written under the old name.
 */
enum class Platform {
    TELEGRAM,

    // named before it has an adapter: the schema, the policy lists and every owner key already carry a
    // platform, and this is what proves they keep two of them apart.
    DISCORD
}

private fun qualified(platform: Platform, id: String): String = "${platform.name.lowercase()}:$id"

/**
 * A person on one platform.
 *
 * Two platforms never share one, and equal ids do not make one identity: an id is opaque text the
 * platform issued, never a number to compare across messengers. Linking somebody's accounts would be
 * a deliberate product feature, not something that happens because two numbers matched.
 */
data class UserRef(val platform: Platform, val id: String) {

    /** One string, for a log line or wherever a single key is unavoidable. */
    val key: String
        get() = qualified(platform, id)

    override fun toString(): String = key
}

/** A conversation on one platform: a chat, a channel, a DM. */
data class ChatRef(val platform: Platform, val id: String) {

    val key: String
        get() = qualified(platform, id)

    override fun toString(): String = key
}

/**
 * What history, the turn lock and a turn's own cancellation belong to: one person in one conversation.
 *
 * The same person writing in two chats is two scopes, and is served in both rather than being told the
 * bot is busy; two people in one chat likewise never read each other's history.
 */
data class ConversationScope(val user: UserRef, val chat: ChatRef) {

    init {
        require(user.platform == chat.platform) {
            "A conversation scope cannot cross platforms: user=[$user] chat=[$chat]"
        }
    }

    val platform: Platform
        get() = user.platform

    override fun toString(): String = "${user.key}@${chat.id}"
}
