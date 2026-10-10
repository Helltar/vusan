package com.helltar.vusan.request

/**
 * Who may use this deployment. Both lists name people and conversations alike, qualified by platform,
 * so an id issued by one messenger can never allow or ban somebody on another.
 *
 * An allowlisted chat admits every message in it, including the rare ones with no personal sender —
 * an anonymous admin, a linked-channel forward — so the chat check never depends on having a user.
 * The ban list wins over the allowlist, which is the only way to shut one person out without closing
 * the chat for everyone.
 *
 * The [owner] is the one person the deployment belongs to: allowed everywhere without being listed,
 * and exempt from what limits everybody else in a group. Every deployment names one (`OWNER_ID`); the
 * type allows none so a policy can be built without a person behind it, in a test or a second adapter.
 */
data class AccessPolicy(
    val allowed: Set<String> = emptySet(),
    val banned: Set<String> = emptySet(),
    val owner: String? = null,
) {

    fun allows(chat: ChatRef, user: UserRef?): Boolean =
        !bans(chat, user) && (chat.key in allowed || (user != null && (user.key in allowed || isOwner(user))))

    fun isOwner(user: UserRef): Boolean =
        owner != null && user.key == owner

    fun bans(chat: ChatRef, user: UserRef?): Boolean =
        chat.key in banned || (user != null && user.key in banned)

    fun denialReason(chat: ChatRef, user: UserRef?): String =
        if (bans(chat, user)) "banned" else "not in allowlist"

    /** Ids both allowed and banned, the owner included. A config mistake worth naming, since the ban wins silently. */
    val contradictory: Set<String>
        get() = (allowed + listOfNotNull(owner)) intersect banned
}
