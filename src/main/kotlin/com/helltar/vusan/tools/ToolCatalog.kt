package com.helltar.vusan.tools

import com.helltar.vusan.llm.ToolDefinition
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

    VOICE_REPLIES("answer out loud with a voice message, or with a round video message of the bot's own face"),
    YOUTUBE("find a video by name or link, send the video or its audio track, or answer from its subtitles"),
    SCHEDULED_TASKS("run something later, once or on a repeating schedule, and list, pause, edit or cancel what is scheduled"),
    WEB_PUBLISHING("put a page, game or small app built in the sandbox on the internet at its own address"),
    POLLS("create a poll or a quiz in the chat and follow who answered what"),
    GIFS("find and send a ready-made GIF and, where the deployment has them, a meme picture or a short clip"),
    TELEGRAM_CHANNELS("recap a public channel by day or week, search its posts, and read the pictures in them"),
    CURRENCY("live exchange rates"),
    FILE_TRANSFERS("send a document to the chat, or download a link into a file");

    val groupName: String = name.lowercase()
}

/** What one [ToolCatalog.load] call did, in the terms the tool reports back to the model. */
data class ToolLoadResult(
    val groups: List<ToolGroup>,
    val toolNames: List<String>,
    val unknown: List<String>,
)

/**
 * The tools of one turn, split into what the model is shown and what it can ask for.
 *
 * Everything registered runs from the first step: a call is resolved against [tools] and not against
 * the definitions the request carried, so a tool the model names before loading its group still runs.
 * Only what [visibleDefinitions] returns is sent, and it widens as [load] is called.
 *
 * [preloaded] is what this conversation asked for on an earlier turn, offered again from the first
 * request — see [LoadedToolGroups] for why. A group that is no longer registered at all, because the
 * chat or the deployment lost it, is dropped rather than remembered.
 */
class ToolCatalog internal constructor(
    entries: List<CatalogEntry>,
    preloaded: Set<ToolGroup> = emptySet(),
    private val onLoad: (List<ToolGroup>) -> Unit = {},
) {

    private val alwaysVisible: List<ToolFunction> = entries.filter { it.group == null }.flatMap { it.tools }

    private val deferred: Map<ToolGroup, List<ToolFunction>> =
        entries.filter { it.group != null }
            .groupBy({ checkNotNull(it.group) }, { it.tools })
            .mapValues { (_, sets) -> sets.flatten() }

    private val loaded: MutableSet<ToolGroup> = deferred.keys.filterTo(mutableSetOf()) { it in preloaded }

    // the loader has nothing to offer when every group this turn registered is gone with the optional
    // service or chat capability behind it, or is already loaded — and an empty menu is one more
    // schema for nothing.
    private val loader: List<ToolFunction> =
        if (deferred.keys.all { it in loaded }) emptyList() else CatalogTools(this).toolFunctions()

    private val byGroupName: Map<String, ToolGroup> = deferred.keys.associateBy { it.groupName }

    /** Every tool this turn may run, offered or not. */
    val tools: List<ToolFunction> = alwaysVisible + deferred.values.flatten() + loader

    private val byName: Map<String, ToolFunction> = tools.associateBy { it.name }

    /** The tool a call names, whether or not its group has been loaded, or `null` for a name nothing answers to. */
    fun find(name: String): ToolFunction? = byName[name]

    /** Bumped whenever [load] widens the visible set, so a run can tell when to re-send its tool list. */
    var revision: Int = 0
        private set

    // registration order, not load order: the tool array is part of the cached prefix on every provider,
    // so two turns that loaded the same groups in a different order must still produce the same request.
    fun visibleDefinitions(): List<ToolDefinition> =
        (alwaysVisible + loader + deferred.filterKeys { it in loaded }.values.flatten()).map { it.definition }

    /** The menu of what is still loadable, or null when this turn has nothing left to offer. */
    fun menu(): String? =
        loadableGroups()
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { "- `${it.groupName}` — ${it.summary}" }

    fun load(names: List<String>): ToolLoadResult {
        val requested = names.mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }.distinct()
        val groups = requested.mapNotNull { byGroupName[it] }

        if (loaded.addAll(groups)) revision++
        if (groups.isNotEmpty()) onLoad(groups)

        return ToolLoadResult(
            groups = groups,
            toolNames = groups.flatMap { group -> deferred[group].orEmpty().map { it.name } },
            unknown = requested.filterNot { it in byGroupName },
        )
    }

    /** Everything still to load, in menu order, for a message that has to name them all. */
    fun groupNames(): List<String> = loadableGroups().map { it.groupName }

    private fun loadableGroups(): List<ToolGroup> = deferred.keys.filterNot { it in loaded }
}

/** One registered tool set and the group, if any, the model has to load before it is offered. */
class CatalogEntry internal constructor(val group: ToolGroup?, val tools: List<ToolFunction>)

class ToolCatalogBuilder internal constructor() {

    private val entries = mutableListOf<CatalogEntry>()

    /** Registers [set] as always visible. */
    fun tools(set: ToolSet) {
        entries += CatalogEntry(null, set.toolFunctions())
    }

    /** Registers [set] behind [group]: runnable from the start, in the request once loaded. */
    fun tools(group: ToolGroup, set: ToolSet) {
        entries += CatalogEntry(group, set.toolFunctions())
    }

    internal fun build(preloaded: Set<ToolGroup>, onLoad: (List<ToolGroup>) -> Unit): ToolCatalog =
        ToolCatalog(entries, preloaded, onLoad)
}

fun toolCatalog(
    preloaded: Set<ToolGroup> = emptySet(),
    onLoad: (List<ToolGroup>) -> Unit = {},
    build: ToolCatalogBuilder.() -> Unit,
): ToolCatalog = ToolCatalogBuilder().apply(build).build(preloaded, onLoad)
