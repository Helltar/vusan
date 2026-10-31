package com.helltar.vusan.tools

import com.helltar.vusan.request.ConversationScope

/**
 * What each conversation already loaded, so its next turn offers the same tools from the first request.
 *
 * That spares the model a `loadTools` call it would make anyway. On an endpoint that takes no tools
 * mid-conversation it also keeps the cached tool array stable across turns, where each load rewrites it; a
 * conversation asking for a voice reply daily would otherwise rebuild the whole prefix every turn.
 *
 * In memory on purpose: a restart costs one extra load per conversation, not worth a table.
 */
class LoadedToolGroups {

    private val byScope =
        object : LinkedHashMap<ConversationScope, LinkedHashSet<ToolGroup>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<ConversationScope, LinkedHashSet<ToolGroup>>?,
            ): Boolean = size > MAX_SCOPES
        }

    fun of(scope: ConversationScope): Set<ToolGroup> =
        synchronized(byScope) { byScope[scope]?.toSet().orEmpty() }

    fun remember(scope: ConversationScope, groups: Collection<ToolGroup>) {
        if (groups.isEmpty()) return

        synchronized(byScope) {
            val kept = byScope.getOrPut(scope) { LinkedHashSet() }

            // re-inserted, so the group used now is the last to fall out
            groups.forEach {
                kept.remove(it)
                kept.add(it)
            }

            while (kept.size > MAX_GROUPS_PER_SCOPE) kept.remove(kept.first())
        }
    }

    private companion object {
        // room for a conversation mixing two capabilities, without one old request pinning the menu open all
        // day.
        const val MAX_GROUPS_PER_SCOPE = 3
        const val MAX_SCOPES = 500
    }
}
