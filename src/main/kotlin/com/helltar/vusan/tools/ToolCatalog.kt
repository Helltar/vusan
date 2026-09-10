package com.helltar.vusan.tools

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.reflect.asTools
import com.helltar.vusan.tools.catalog.CatalogTools

/**
 * A capability whose tool schemas are withheld until the model asks for them.
 *
 * Every schema the request carries is re-sent on every step of a turn and is charged to the
 * conversation budget in `ContextWindowPolicy` — offering all of them at once costs more than a
 * small model's whole window, and most turns call none of these. [summary] is the only thing the
 * model reads before loading a group, so it names the capability the way a person would ask for it
 * rather than the tools behind it.
 */
enum class ToolGroup(val summary: String) {

    IMAGE_GENERATION("draw a picture from a description, edit a picture the user sent, or merge several into one"),
    VOICE_REPLIES("answer out loud with a voice message, or with a round video message of the bot's own face"),
    YOUTUBE("find a video by name or link, send the video or its audio track, or answer from its subtitles"),
    SCHEDULED_TASKS("run something later, once or on a repeating schedule, check back after an event, and list, pause, edit or cancel what is scheduled"),
    WEB_PUBLISHING("put a page, game or small app built in the workspace on the internet at its own address"),
    POLLS("create a poll or a quiz in the chat and follow who answered what"),
    GIFS("find and send an animated GIF"),
    TELEGRAM_CHANNELS("recap a public channel by day or week, search its posts, and read the pictures in them"),
    CURRENCY("live exchange rates"),
    FILE_TRANSFERS("send a document to the chat, or download a link into a file");

    val groupName: String = name.lowercase()
}

/** What one [ToolCatalog.load] call did, in the terms the tool reports back to the model. */
data class ToolLoadResult(
    val groups: List<ToolGroup>,
    val toolNames: List<String>,
    val unknown: List<String>
)

/**
 * The tools of one turn, split into what the model is shown and what it can ask for.
 *
 * The registry holds everything from the first step: koog resolves a call against it and not against
 * the descriptors the request carried, so a tool the model names before loading its group still runs.
 * Only what [visibleDescriptors] returns is sent, and it widens as [load] is called.
 */
class ToolCatalog internal constructor(entries: List<CatalogEntry>) {

    private val alwaysVisible: List<ToolBase<*, *>> = entries.filter { it.group == null }.flatMap { it.tools }

    private val deferred: Map<ToolGroup, List<ToolBase<*, *>>> =
        entries.filter { it.group != null }
            .groupBy({ checkNotNull(it.group) }, { it.tools })
            .mapValues { (_, sets) -> sets.flatten() }

    // the loader has nothing to offer when every group this turn registered is gone with the optional
    // service or chat capability behind it, and an empty menu is one more schema for nothing.
    private val loader: List<ToolBase<*, *>> =
        if (deferred.isEmpty()) emptyList() else CatalogTools(this).let { it::class.asTools(it) }

    private val byGroupName: Map<String, ToolGroup> = deferred.keys.associateBy { it.groupName }
    private val loaded = mutableSetOf<ToolGroup>()

    val registry: ToolRegistry =
        ToolRegistry { tools(alwaysVisible + deferred.values.flatten() + loader) }

    /** Bumped whenever [load] widens the visible set, so a run can tell when to re-send its tool list. */
    var revision: Int = 0
        private set

    // registration order, not load order: the tool array is part of the cached prefix on every provider,
    // so two turns that loaded the same groups in a different order must still produce the same request.
    fun visibleDescriptors(): List<ToolDescriptor> =
        (alwaysVisible + loader + deferred.filterKeys { it in loaded }.values.flatten()).map { it.descriptor }

    /** The group menu for the system prompt, or null when this turn deferred nothing. */
    fun menu(): String? =
        deferred.keys
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { "- `${it.groupName}` — ${it.summary}" }

    fun load(names: List<String>): ToolLoadResult {
        val requested = names.mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }.distinct()
        val groups = requested.mapNotNull { byGroupName[it] }

        if (loaded.addAll(groups)) revision++

        return ToolLoadResult(
            groups = groups,
            toolNames = groups.flatMap { group -> deferred[group].orEmpty().map { it.name } },
            unknown = requested.filterNot { it in byGroupName }
        )
    }

    /** Every group this turn registered, in menu order, for a message that has to name them all. */
    fun groupNames(): List<String> = byGroupName.keys.toList()
}

/** One registered tool set and the group, if any, the model has to load before it is offered. */
class CatalogEntry internal constructor(val group: ToolGroup?, val tools: List<ToolBase<*, *>>)

class ToolCatalogBuilder internal constructor() {

    private val entries = mutableListOf<CatalogEntry>()

    /** Registers [set] as always visible. */
    fun tools(set: ToolSet) {
        entries += CatalogEntry(null, set.toolList())
    }

    /** Registers [set] behind [group]: in the registry from the start, in the request once loaded. */
    fun tools(group: ToolGroup, set: ToolSet) {
        entries += CatalogEntry(group, set.toolList())
    }

    internal fun build(): ToolCatalog = ToolCatalog(entries)
}

fun toolCatalog(build: ToolCatalogBuilder.() -> Unit): ToolCatalog = ToolCatalogBuilder().apply(build).build()

private fun ToolSet.toolList(): List<ToolBase<*, *>> = this::class.asTools(this)
