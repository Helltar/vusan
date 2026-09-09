package com.helltar.vusan.tools.memory

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.agent.memory.MemoryRepository
import com.helltar.vusan.agent.memory.memoryOwner
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.tools.suspendToolGuard

private const val MAX_MEMORY_CHARS = 500
private const val NO_PERSONAL_MEMORY =
    "Telegram delivers this sender under an account shared with other people, so there is no personal " +
        "memory here. Group memory still works; say so instead of saving the detail."

@Suppress("unused")
class MemoryTools(private val memory: MemoryRepository, private val context: RequestContext) : ToolSet {

    @Tool
    @LLMDescription(MemoryToolDescriptions.REMEMBER_ABOUT_ME)
    suspend fun rememberAboutMe(
        @LLMDescription(MemoryToolDescriptions.REMEMBER_ABOUT_ME_DETAIL)
        detail: String
    ): String = suspendToolGuard {
        if (!context.sender.isPerson) return@suspendToolGuard NO_PERSONAL_MEMORY

        detail.collapseWhitespaceAndCap(MAX_MEMORY_CHARS)?.let { clean ->
            val id = memory.add(context.user.memoryOwner, clean)
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
        if (context.chat.isPrivate)
            return@suspendToolGuard "No shared group memory in a private chat — use rememberAboutMe for personal details."

        detail.collapseWhitespaceAndCap(MAX_MEMORY_CHARS)?.let { clean ->
            val id = memory.add(context.chatRef.memoryOwner, clean)
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
        if (memory.forget(id, context.user, context.chatRef))
            "Forgot memory #$id."
        else
            "No memory #$id found in your memory or this chat's memory."
    }

    @Tool
    @LLMDescription(MemoryToolDescriptions.FORGET_EVERYTHING_ABOUT_ME)
    suspend fun forgetEverythingAboutMe(): String = suspendToolGuard {
        if (!context.sender.isPerson) return@suspendToolGuard NO_PERSONAL_MEMORY

        val removed = memory.clearScope(context.user.memoryOwner)
        "Cleared your personal memory ($removed item(s) removed). Chat history and group memory are untouched."
    }
}
