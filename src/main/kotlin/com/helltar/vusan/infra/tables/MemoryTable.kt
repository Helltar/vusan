package com.helltar.vusan.infra.tables

import com.helltar.vusan.agent.memory.MemoryScope
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

object MemoryTable : LongIdTable("memories") {

    val scope = enumerationByName<MemoryScope>("scope", 16)
    val ownerId = long("owner_id")
    val content = text("content")
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }

    init {
        // Every lookup filters by (scope, ownerId): user memory by userId, chat memory by chatId.
        index(false, scope, ownerId)
    }
}
