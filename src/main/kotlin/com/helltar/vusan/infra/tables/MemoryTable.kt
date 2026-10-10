package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

object MemoryTable : LongIdTable("memories") {

    val platform = platformColumn()
    // the name of a `MemoryScope`, which the repository that owns the enum maps; the table knows only the text
    val scope = varchar("scope", 16)
    val ownerId = externalId("owner_id")
    val content = text("content")

    init {
        // every lookup filters by the whole owner: user memory by their id, chat memory by the chat's.
        index(false, platform, scope, ownerId)
    }
}
