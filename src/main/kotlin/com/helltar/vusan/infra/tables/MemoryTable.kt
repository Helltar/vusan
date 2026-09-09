package com.helltar.vusan.infra.tables

import com.helltar.vusan.agent.memory.MemoryScope
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

object MemoryTable : LongIdTable("memories") {

    val platform = platformColumn()
    val scope = enumerationByName<MemoryScope>("scope", 16)
    val ownerId = externalId("owner_id")
    val content = text("content")
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    init {
        // every lookup filters by the whole owner: user memory by their id, chat memory by the chat's.
        index(false, platform, scope, ownerId)
    }
}
