package com.helltar.vusan.infra.tables

import com.helltar.vusan.infra.metrics.PeerKind
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

object PeersTable : Table("peers") {

    val peerId = long("peer_id")
    val kind = enumerationByName<PeerKind>("kind", 8)

    // meaningful only for CHAT rows ("private" | "group"); null for USER rows.
    val chatType = varchar("chat_type", 16).nullable()

    val firstSeen = timestamp("first_seen")
    val lastSeen = timestamp("last_seen")

    // composite key: in private chats the chat id equals the user id, so the same
    // peer id legitimately appears once per kind.
    override val primaryKey = PrimaryKey(peerId, kind)
}
