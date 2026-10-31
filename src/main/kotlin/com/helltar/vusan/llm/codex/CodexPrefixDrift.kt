package com.helltar.vusan.llm.codex

import com.helltar.vusan.llm.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Where a conversation's request stops repeating the previous one.
 *
 * The provider caches a prefix, so a call reads back up to the first difference from the last request: one
 * that only appends reads nearly everything, one that changed an early item pays for the rest. The log's
 * cached share says how much was lost, not where; this says where, by position and kind, without logging
 * content. Only fingerprints are kept, for the most recent conversations.
 */
internal class CodexPrefixDrift {

    private val lastSeen =
        object : LinkedHashMap<String, List<Int>>(TRACKED_SESSIONS, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Int>>): Boolean =
                size > TRACKED_SESSIONS
        }

    /** The part of a call's log line placing [request] against [session]'s previous one. */
    fun describe(session: String, request: JsonObject): String {
        val items = request.prefixItems()
        // the element's own hash, not its serialization's: an input image is megabytes of base64
        val current = items.map { it.hashCode() }
        val previous = synchronized(lastSeen) { lastSeen.put(session, current) }

        val summary = "items=[${items.size}]"
        if (previous == null) return "$summary drift=[first]"

        val same = previous.zip(current).takeWhile { (before, now) -> before == now }.size

        // everything the last request held is still there, so this one only appended.
        if (same == previous.size) return "$summary drift=[none]"

        return "$summary drift=[$same of ${previous.size}: ${items.getOrNull(same)?.kindLabel(same) ?: "shorter"}]"
    }

    private companion object {
        const val TRACKED_SESSIONS = 256
        const val LOAD_FACTOR = 0.75f
    }
}

// the provider's reading order: the tool list, then the input item by item.
private fun JsonObject.prefixItems(): List<JsonElement> =
    listOf(this["tools"] ?: JsonArray(emptyList())) + (this["input"] as? JsonArray).orEmpty()

// the item's kind and, for a message, the block it opens with: a prompt tag, never content.
private fun JsonElement.kindLabel(index: Int): String {
    if (index == 0) return "tools"

    val item = this as? JsonObject ?: return "item"
    val type = item.string("type") ?: "message"
    val role = item.string("role") ?: return type

    val opening =
        ((item["content"] as? JsonArray)?.firstOrNull() as? JsonObject)?.string("text")
            ?.let { OPENING_TAG.matchAt(it.trimStart(), 0)?.value }

    return listOfNotNull("$type/$role", opening).joinToString(" ")
}

private val OPENING_TAG = Regex("<[a-z_]{1,40}>")
