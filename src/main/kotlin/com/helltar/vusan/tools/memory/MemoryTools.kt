package com.helltar.vusan.tools.memory

import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.agent.memory.MemoryOwner
import com.helltar.vusan.agent.memory.MemoryRepository
import com.helltar.vusan.agent.memory.memoryOwner
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.tools.suspendToolGuard

private const val MAX_MEMORY_CHARS = 500
private const val NO_PERSONAL_MEMORY =
    "The messenger delivers this sender under an account shared with other people, so there is no personal " +
        "memory here. Group memory still works; say so instead of saving the detail."

class MemoryTools(private val memory: MemoryRepository, private val context: RequestContext) : ToolSet {

    @Tool(MemoryToolDescriptions.REMEMBER_ABOUT_ME)
    suspend fun rememberAboutMe(
        @Arg(MemoryToolDescriptions.REMEMBER_ABOUT_ME_DETAIL)
        detail: String,
        @Arg(MemoryToolDescriptions.REPLACES_ID)
        replacesId: Long = 0,
    ): String = suspendToolGuard {
        if (!context.sender.isPerson) return@suspendToolGuard NO_PERSONAL_MEMORY

        save(context.user.memoryOwner, "your personal memory", detail, replacesId)
    }

    @Tool(MemoryToolDescriptions.REMEMBER_ABOUT_GROUP)
    suspend fun rememberAboutGroup(
        @Arg(MemoryToolDescriptions.REMEMBER_ABOUT_GROUP_DETAIL)
        detail: String,
        @Arg(MemoryToolDescriptions.REPLACES_ID)
        replacesId: Long = 0,
    ): String = suspendToolGuard {
        if (context.chat.isPrivate)
            return@suspendToolGuard "No shared group memory in a private chat — use rememberAboutMe for personal details."

        save(context.chatRef.memoryOwner, "this group's memory", detail, replacesId)
    }

    @Tool(MemoryToolDescriptions.FORGET_MEMORY)
    suspend fun forgetMemory(
        @Arg(MemoryToolDescriptions.FORGET_MEMORY_ID)
        id: Long,
    ): String = suspendToolGuard {
        if (memory.forget(id, context.user, context.chatRef))
            "Forgot memory #$id."
        else
            "No memory #$id found in your memory or this chat's memory."
    }

    @Tool(MemoryToolDescriptions.FORGET_EVERYTHING_ABOUT_ME)
    suspend fun forgetEverythingAboutMe(): String = suspendToolGuard {
        if (!context.sender.isPerson) return@suspendToolGuard NO_PERSONAL_MEMORY

        val removed = memory.clearScope(context.user.memoryOwner)
        "Cleared your personal memory ($removed item(s) removed). Chat history and group memory are untouched."
    }

    // a wrong id still saves the detail: losing what the user asked to keep is worse than a stale
    // entry left beside it, and the reply says which of the two happened. a detail over the limit is
    // refused rather than cut: an item that grows is appended to, so a cut would drop the newest part.
    private suspend fun save(owner: MemoryOwner, where: String, detail: String, replacesId: Long): String {
        val clean =
            detail.collapseWhitespaceAndCap(Int.MAX_VALUE)
                ?: return "Nothing to remember — the detail was empty."

        require(clean.length <= MAX_MEMORY_CHARS) {
            "The detail is ${clean.length} characters and an item holds at most $MAX_MEMORY_CHARS, so nothing " +
                "was saved. Write it again as a shorter summary of what still holds, leaving out anything that reads " +
                "as a running log, such as dated entries — the chat history already keeps those."
        }

        if (replacesId <= 0) return "Saved to $where (#${memory.add(owner, clean)}): $clean"

        return memory.replace(owner, replacesId, clean)
            ?.let { "Saved to $where (#$it) in place of #$replacesId: $clean" }
            ?: "Saved to $where (#${memory.add(owner, clean)}) as a new item, because it holds no #$replacesId: $clean"
    }
}
