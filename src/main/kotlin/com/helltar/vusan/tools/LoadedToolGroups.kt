package com.helltar.vusan.tools

import com.helltar.vusan.request.ConversationScope

/**
 * What each conversation has already loaded, so its next turn can offer the same tools from its
 * first request.
 *
 * A `loadTools` call rewrites the tool array mid-turn, and that array is part of the prompt prefix
 * providers cache — so without this a conversation that draws a picture every day would rebuild the
 * whole prefix on every turn, which costs far more than the schemas it saved. Offering last turn's
 * groups again keeps the array stable across turns: only the first use of a capability pays.
 *
 * Process memory on purpose. Losing it to a restart costs one extra load per conversation, which is
 * not worth a table and a schema version.
 */
class LoadedToolGroups {

    private companion object {
        // room for a conversation that mixes two capabilities, without one old request pinning the
        // whole menu open for the rest of the day.
        const val MAX_GROUPS_PER_SCOPE = 3
        const val MAX_SCOPES = 500
    }

    private val byScope =
        object : LinkedHashMap<ConversationScope, LinkedHashSet<ToolGroup>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<ConversationScope, LinkedHashSet<ToolGroup>>?
            ): Boolean = size > MAX_SCOPES
        }

    fun of(scope: ConversationScope): Set<ToolGroup> =
        synchronized(byScope) { byScope[scope]?.toSet().orEmpty() }

    fun remember(scope: ConversationScope, groups: Collection<ToolGroup>) {
        if (groups.isEmpty()) return

        synchronized(byScope) {
            val kept = byScope.getOrPut(scope) { LinkedHashSet() }

            // re-inserted rather than kept in place, so the group used now is the last to fall out
            groups.forEach {
                kept.remove(it)
                kept.add(it)
            }

            while (kept.size > MAX_GROUPS_PER_SCOPE) kept.remove(kept.first())
        }
    }
}
