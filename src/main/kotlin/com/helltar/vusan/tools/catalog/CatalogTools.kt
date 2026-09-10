package com.helltar.vusan.tools.catalog

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.tools.ToolCatalog
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard

@Suppress("unused")
class CatalogTools(private val catalog: ToolCatalog) : ToolSet {

    private companion object {
        const val MAX_GROUPS_CHARS = 200
    }

    @Tool
    @LLMDescription(CatalogToolDescriptions.LOAD_TOOLS)
    suspend fun loadTools(
        @LLMDescription(CatalogToolDescriptions.GROUPS)
        groups: String
    ): String = suspendToolGuard {
        val result = catalog.load(groups.requireToolText("Groups", MAX_GROUPS_CHARS).split(","))

        require(result.groups.isNotEmpty()) {
            "no tool group is named ${result.unknown.joinToString(", ")}; " +
                    "the groups are: ${catalog.groupNames().joinToString(", ")}"
        }

        buildString {
            append("Loaded ${result.groups.joinToString(", ") { "`${it.groupName}`" }}: ")
            append("${result.toolNames.joinToString(", ")}. ")
            append("Their definitions reach you with your next step — call them in this same turn.")

            if (result.unknown.isNotEmpty()) {
                append("\nNot loaded, no such group: ${result.unknown.joinToString(", ")}. ")
                append("The groups are: ${catalog.groupNames().joinToString(", ")}.")
            }
        }
    }
}
