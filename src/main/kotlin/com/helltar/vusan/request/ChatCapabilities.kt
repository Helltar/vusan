package com.helltar.vusan.request

/**
 * What the bot may post in one chat, how fast, and which of the chat's settings it may change. Groups can
 * forbid whole content kinds, and a plain-member bot finds out only when a send is rejected, after the turn is
 * paid for. Resolving this up front keeps refused tools out of the catalog and explains the rest to the agent.
 *
 * Every posting kind defaults to allowed: a failed lookup or an unrestricted chat must never read as a lost
 * ability. The admin rights default to absent instead: most chats never grant them, and a tool offered
 * without one could only fail.
 */
data class ChatCapabilities(
    val photos: Boolean = true,
    val videos: Boolean = true,
    val audios: Boolean = true,
    val documents: Boolean = true,
    val voiceNotes: Boolean = true,
    val videoNotes: Boolean = true,
    val polls: Boolean = true,
    // telegram groups animations, games and stickers under one permission
    val stickersAndAnimations: Boolean = true,
    val reactions: Boolean = true,
    /** Seconds a non-privileged member waits between messages; `0` without slow mode. */
    val slowModeSeconds: Int = 0,
    /** The bot is an admin who may change the chat's photo, title and description. */
    val chatInfo: Boolean = false,
    /** The chat has forum topics and the bot is an admin who may open, rename, close and reopen them. */
    val topics: Boolean = false,
) {

    /** The refused content kinds, named as the prompt and the logs want them. */
    val restrictedKinds: List<String>
        get() = buildList {
            if (!photos) add("photos")
            if (!videos) add("videos")
            if (!audios) add("audio")
            if (!documents) add("documents")
            if (!voiceNotes) add("voice messages")
            if (!videoNotes) add("video notes")
            if (!polls) add("polls")
            if (!stickersAndAnimations) add("stickers and GIFs")
            if (!reactions) add("reactions")
        }

    companion object {
        val UNRESTRICTED = ChatCapabilities()
    }
}
