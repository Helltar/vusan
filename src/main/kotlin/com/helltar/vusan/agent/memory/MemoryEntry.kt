package com.helltar.vusan.agent.memory

import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.request.Platform
import com.helltar.vusan.request.UserRef
import java.time.Instant

/**
 * Scope of a durable [MemoryEntry], separate from the rolling conversation history:
 * - [USER] entries belong to the sender and follow that person across DMs and groups.
 * - [CHAT] entries belong to the group and are shared by everyone in it.
 */
enum class MemoryScope { USER, CHAT }

/**
 * Whose memory a row is. Scope and owner id travel together so neither can be paired with the other's
 * value: a chat id read as a person would hand a whole group somebody's personal memory.
 */
data class MemoryOwner(val platform: Platform, val scope: MemoryScope, val id: String)

val UserRef.memoryOwner: MemoryOwner
    get() = MemoryOwner(platform, MemoryScope.USER, id)

val ChatRef.memoryOwner: MemoryOwner
    get() = MemoryOwner(platform, MemoryScope.CHAT, id)

data class MemoryEntry(val id: Long, val content: String, val createdAt: Instant)
