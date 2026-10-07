package com.helltar.vusan.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Where a conversation's request stops repeating the one before it.
 *
 * The provider caches a prefix, so what a call can read back is decided by the first place its request
 * differs from the last one: a request that only appends reads nearly everything, one that changed an
 * early item reads up to that item and pays for the rest. The cached share in the log says how much was
 * lost and nothing about where; this says where, by position and kind, without logging what was said.
 *
 * Only fingerprints are kept, for the conversations seen most recently.
 */
internal class CodexPrefixDrift {

    private val lastSeen =
        object : LinkedHashMap<String, List<Int>>(TRACKED_SESSIONS, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Int>>): Boolean =
                size > TRACKED_SESSIONS
        }

    /** The part of a call's log line that places [request] against the previous one of [session]. */
    fun describe(session: String, request: JsonObject): String {
        val items = request.prefixItems()
        val current = items.map { it.toString().hashCode() }
        val previous = synchronized(lastSeen) { lastSeen.put(session, current) }

        val summary = "items=[${items.size}]"
        if (previous == null) return "$summary drift=[first]"

        val same = previous.zip(current).takeWhile { (before, now) -> before == now }.size

        // everything the last request held is still there, so this one only added to it.
        if (same == previous.size) return "$summary drift=[none]"

        return "$summary drift=[$same of ${previous.size}: ${items.getOrNull(same)?.kindLabel(same) ?: "shorter"}]"
    }

    private companion object {
        const val TRACKED_SESSIONS = 256
        const val LOAD_FACTOR = 0.75f
    }
}

// the order the provider reads a request in: the tool list first, then the input, item by item.
private fun JsonObject.prefixItems(): List<JsonElement> =
    listOf(this["tools"] ?: JsonArray(emptyList())) + (this["input"] as? JsonArray).orEmpty()

// what kind of item it is, and for a message the block it opens with — a tag of the prompt's own, never
// its content.
private fun JsonElement.kindLabel(index: Int): String {
    if (index == 0) return "tools"

    val item = this as? JsonObject ?: return "item"
    val type = item.text("type") ?: "message"
    val role = item.text("role") ?: return type

    val opening =
        ((item["content"] as? JsonArray)?.firstOrNull() as? JsonObject)?.text("text")
            ?.let { OPENING_TAG.matchAt(it.trimStart(), 0)?.value }

    return listOfNotNull("$type/$role", opening).joinToString(" ")
}

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private val OPENING_TAG = Regex("<[a-z_]{1,40}>")
