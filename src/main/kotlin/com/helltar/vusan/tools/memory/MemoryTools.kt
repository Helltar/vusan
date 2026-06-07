package com.helltar.vusan.tools.memory

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.agent.memory.MemoryRepository
import com.helltar.vusan.agent.memory.MemoryScope
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.tools.suspendToolGuard

private const val MAX_MEMORY_CHARS = 500

@Suppress("unused")
class MemoryTools(private val memory: MemoryRepository, private val context: RequestContext) : ToolSet {

    @Tool
    @LLMDescription(MemoryToolDescriptions.REMEMBER_ABOUT_ME)
    suspend fun rememberAboutMe(
        @LLMDescription(MemoryToolDescriptions.REMEMBER_ABOUT_ME_DETAIL)
        detail: String
    ): String = suspendToolGuard {
        val userId = context.userId

        check(userId != 0L) { "User ID is unavailable for memory tools" }

        detail.collapseWhitespaceAndCap(MAX_MEMORY_CHARS)?.let { clean ->
            val id = memory.add(MemoryScope.USER, userId, clean)
            "Saved to your personal memory (#$id): $clean"
        }
            ?: "Nothing to remember — the detail was empty."
    }

    @Tool
    @LLMDescription(MemoryToolDescriptions.REMEMBER_ABOUT_GROUP)
    suspend fun rememberAboutGroup(
        @LLMDescription(MemoryToolDescriptions.REMEMBER_ABOUT_GROUP_DETAIL)
        detail: String
    ): String = suspendToolGuard {
        if (context.chatIsPrivate)
            return@suspendToolGuard "No shared group memory in a private chat — use rememberAboutMe for personal details."

        val chatId = context.chatId

        check(chatId != 0L) { "Chat ID is unavailable for memory tools" }

        detail.collapseWhitespaceAndCap(MAX_MEMORY_CHARS)?.let { clean ->
            val id = memory.add(MemoryScope.CHAT, chatId, clean)
            "Saved to this group's memory (#$id): $clean"
        }
            ?: "Nothing to remember — the detail was empty."
    }

    @Tool
    @LLMDescription(MemoryToolDescriptions.FORGET_MEMORY)
    suspend fun forgetMemory(
        @LLMDescription(MemoryToolDescriptions.FORGET_MEMORY_ID)
        id: Long
    ): String = suspendToolGuard {
        if (memory.forget(id, context.userId, context.chatId))
            "Forgot memory #$id."
        else
            "No memory #$id found in your memory or this chat's memory."
    }

    @Tool
    @LLMDescription(MemoryToolDescriptions.FORGET_EVERYTHING_ABOUT_ME)
    suspend fun forgetEverythingAboutMe(): String = suspendToolGuard {
        val userId = context.userId
        check(userId != 0L) { "User ID is unavailable for memory tools" }
        val removed = memory.clearScope(MemoryScope.USER, userId)
        "Cleared your personal memory ($removed item(s) removed). Chat history and group memory are untouched."
    }
}
