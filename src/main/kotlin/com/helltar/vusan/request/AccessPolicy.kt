package com.helltar.vusan.request

/**
 * Who may use this deployment. Both lists name people and conversations, qualified by platform, so one
 * messenger's id never allows or bans someone on another.
 *
 * An allowlisted chat admits every message in it, including ones without a personal sender (an anonymous
 * admin, a linked-channel forward), so the chat check never needs a user. The ban list wins over the
 * allowlist, the only way to shut one person out without closing the chat.
 *
 * The [owner] is the person the deployment belongs to: allowed everywhere unlisted and exempt from group
 * limits. Every deployment names one (`OWNER_ID`); the type allows none so a policy can be built without a
 * person, in a test or a second adapter.
 */
data class AccessPolicy(
    val allowed: Set<String> = emptySet(),
    val banned: Set<String> = emptySet(),
    val owner: String? = null,
) {

    /**
     * Ids both allowed and banned, the owner included: a config mistake worth naming, since the ban wins
     * silently.
     */
    val contradictory: Set<String>
        get() = (allowed + listOfNotNull(owner)) intersect banned

    fun allows(chat: ChatRef, user: UserRef?): Boolean =
        !bans(chat, user) && (chat.key in allowed || (user != null && (user.key in allowed || isOwner(user))))

    fun isOwner(user: UserRef): Boolean =
        owner != null && user.key == owner

    fun bans(chat: ChatRef, user: UserRef?): Boolean =
        chat.key in banned || (user != null && user.key in banned)

    fun denialReason(chat: ChatRef, user: UserRef?): String =
        if (bans(chat, user)) "banned" else "not in allowlist"
}
