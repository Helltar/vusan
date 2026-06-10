package com.helltar.vusan.infra.metrics

import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.PeersTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

enum class PeerKind { CHAT, USER }

data class PeerStats(
    val chatsKnown: Long,
    val usersKnown: Long,
    val chatsActive24h: Long,
    val chatsActive7d: Long,
    val usersActive24h: Long,
    val usersActive7d: Long
)

/**
 * Persistent record of every chat and user the agent has actually conversed with,
 * backing the unique/active audience gauges. Unlike `chat_messages`, rows here are
 * never trimmed or cleared, so the counts survive restarts and history wipes.
 */
class PeersRepository {

    suspend fun touch(chatId: Long, chatIsPrivate: Boolean, userId: Long, now: Instant = Instant.now()) {
        dbTransaction {
            upsertPeer(chatId, PeerKind.CHAT, chatType = if (chatIsPrivate) "private" else "group", now = now)
            upsertPeer(userId, PeerKind.USER, chatType = null, now = now)
        }
    }

    suspend fun stats(now: Instant = Instant.now()): PeerStats =
        dbTransaction {
            PeerStats(
                chatsKnown = countPeers(PeerKind.CHAT),
                usersKnown = countPeers(PeerKind.USER),
                chatsActive24h = countPeers(PeerKind.CHAT, activeSince = now.minus(DAY.toJavaDuration())),
                chatsActive7d = countPeers(PeerKind.CHAT, activeSince = now.minus(WEEK.toJavaDuration())),
                usersActive24h = countPeers(PeerKind.USER, activeSince = now.minus(DAY.toJavaDuration())),
                usersActive7d = countPeers(PeerKind.USER, activeSince = now.minus(WEEK.toJavaDuration()))
            )
        }

    private fun upsertPeer(id: Long, kind: PeerKind, chatType: String?, now: Instant) {
        PeersTable.upsert(onUpdate = { it[PeersTable.lastSeen] = now }) {
            it[peerId] = id
            it[PeersTable.kind] = kind
            it[PeersTable.chatType] = chatType
            it[firstSeen] = now
            it[lastSeen] = now
        }
    }

    private fun countPeers(kind: PeerKind, activeSince: Instant? = null): Long =
        PeersTable.selectAll()
            .where {
                val byKind = PeersTable.kind eq kind
                activeSince?.let { byKind and (PeersTable.lastSeen greaterEq it) } ?: byKind
            }
            .count()
}

private val DAY = 24.hours
private val WEEK = 7.days
