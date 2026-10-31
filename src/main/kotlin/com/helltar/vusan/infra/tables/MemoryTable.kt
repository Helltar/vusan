package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

object MemoryTable : LongIdTable("memories") {

    val platform = platformColumn()
    // a `MemoryScope` name, mapped by the repository that owns the enum
    val scope = varchar("scope", 16)
    val ownerId = externalId("owner_id")
    val content = text("content")

    init {
        // every lookup filters by the whole owner: user memory by their id, chat memory by the chat's.
        index(false, platform, scope, ownerId)
    }
}
