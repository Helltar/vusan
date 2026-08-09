package com.helltar.vusan.request

/**
 * What the bot is allowed to put into one chat, and how fast. Groups can forbid whole content kinds,
 * and a bot that is a plain member is bound by that like anyone else — but it only finds out when a
 * send is rejected, after the turn that produced it has already been paid for. Resolving this up front
 * lets the tools that would be refused stay out of the agent's registry and the rest be explained to it.
 *
 * Every field defaults to allowed: a lookup that failed, or a chat nobody restricted, must never read
 * as the bot having lost an ability.
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
    /** Seconds a non-privileged member must wait between messages; `0` when slow mode is off. */
    val slowModeSeconds: Int = 0
) {

    companion object {
        val UNRESTRICTED = ChatCapabilities()
    }

    /** The refused content kinds, named the way the agent-facing prompt and the logs both want them. */
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
}
