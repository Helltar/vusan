package com.helltar.vusan.request

/**
 * A messenger vusan is reachable on. The name is stored as-is in every table holding owned state, so it is
 * schema: renaming one orphans every row written under the old name.
 */
enum class Platform {
    TELEGRAM,

    // named before it has an adapter: the schema, policy lists and owner keys already carry a platform, and
    // this proves they keep two apart.
    DISCORD
}

private fun qualified(platform: Platform, id: String): String = "${platform.name.lowercase()}:$id"

/**
 * A person on one platform. Equal ids on two platforms are not one identity: an id is opaque text the
 * platform issued, never a number to compare across messengers. Linking accounts would be a deliberate
 * feature, not a coincidence of numbers.
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
 * What history, the turn lock and a turn's cancellation belong to: one person in one conversation. The same
 * person in two chats is two scopes and is served in both; two people in one chat never read each other's
 * history.
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
