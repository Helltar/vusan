package com.helltar.vusan.agent.memory

import java.time.Instant

/**
 * Scope of a durable [MemoryEntry], separate from the rolling conversation history:
 * - [USER] entries are keyed by the sender's `userId` and follow the person across DMs and groups.
 * - [CHAT] entries are keyed by the `chatId` and are shared by everyone in that group.
 */
enum class MemoryScope { USER, CHAT }

data class MemoryEntry(val id: Long, val content: String, val createdAt: Instant)
