package com.helltar.vusan.agent.memory

import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.request.Platform
import com.helltar.vusan.request.UserRef

/**
 * Scope of a durable [MemoryEntry], apart from the rolling history: [USER] entries follow the sender across
 * DMs and groups, [CHAT] entries are shared by everyone in the group.
 */
enum class MemoryScope { USER, CHAT }

/**
 * Whose memory a row is. Scope and owner id travel together so neither pairs with the other's value: a chat
 * id read as a person would hand a whole group someone's personal memory.
 */
data class MemoryOwner(val platform: Platform, val scope: MemoryScope, val id: String)

val UserRef.memoryOwner: MemoryOwner
    get() = MemoryOwner(platform, MemoryScope.USER, id)

val ChatRef.memoryOwner: MemoryOwner
    get() = MemoryOwner(platform, MemoryScope.CHAT, id)

data class MemoryEntry(val id: Long, val content: String)
