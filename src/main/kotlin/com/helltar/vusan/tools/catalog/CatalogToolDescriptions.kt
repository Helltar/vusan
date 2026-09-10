package com.helltar.vusan.tools.catalog

internal object CatalogToolDescriptions {

    const val LOAD_TOOLS =
        "Loads the tools of one or more capability groups so that you can call them. " +
                "`<tool_groups>` lists every group this chat has and what each one covers; the tools behind a group are not offered to you until you load it. " +
                "Load a group the moment the request needs it, then call its tools in the same turn — never tell the user that a listed capability is unavailable. " +
                "Loading a group twice is harmless."

    const val GROUPS =
        "Comma-separated group names, spelled exactly as `<tool_groups>` lists them, for example `image_generation` or `voice_replies,scheduled_tasks`. " +
                "Name every group the task needs in one call."
}
