package com.helltar.vusan.agent.presence

import com.helltar.vusan.infra.Db.dbTransaction
import com.helltar.vusan.infra.tables.LeaveAloneTable
import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.request.UserRef
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/**
 * The people who asked the bot not to write to them, chat by chat, and until when.
 *
 * A request is what a person said in their own words — «leave me alone», «stop writing to me» — recorded by
 * the turn that heard it. It stops only what the bot would send them unasked: a look's reaction or line,
 * a follow-up guessed to be for it. A message they send the bot themselves is answered as before.
 *
 * Kept in the database because it is a promise: a restart must not be followed by the bot writing to
 * somebody who asked it not to. A request past its end is as good as gone; the maintenance pass drops it.
 */
class LeaveAloneRepository {

    /** Records that [user] asked to be left alone in [chat] until [until]; a request already there is replaced. */
    suspend fun store(chat: ChatRef, user: UserRef, until: Instant) {
        require(user.platform == chat.platform) { "A request cannot cross platforms: user=[$user] chat=[$chat]" }

        dbTransaction {
            LeaveAloneTable.upsert(
                onUpdate = { it[LeaveAloneTable.endsAt] = until },
            ) {
                it[platform] = chat.platform
                it[chatId] = chat.id
                it[userId] = user.id
                it[endsAt] = until
            }
        }
    }

    /** Ends the request early; answers whether there was one. */
    suspend fun lift(chat: ChatRef, user: UserRef): Boolean = dbTransaction {
        LeaveAloneTable.deleteWhere { forUser(chat, user) } > 0
    }

    /** When [user]'s request in [chat] ends, or `null` when none holds at [now]. */
    suspend fun activeUntil(chat: ChatRef, user: UserRef, now: Instant): Instant? = dbTransaction {
        LeaveAloneTable
            .select(LeaveAloneTable.endsAt)
            .where { forUser(chat, user) and (LeaveAloneTable.endsAt greater now) }
            .firstOrNull()
            ?.get(LeaveAloneTable.endsAt)
    }

    /** Everyone whose request in [chat] holds at [now], by their id on the chat's platform, with its end. */
    suspend fun activeInChat(chat: ChatRef, now: Instant): Map<String, Instant> = dbTransaction {
        LeaveAloneTable
            .select(LeaveAloneTable.userId, LeaveAloneTable.endsAt)
            .where { inChat(chat) and (LeaveAloneTable.endsAt greater now) }
            .associate { it[LeaveAloneTable.userId] to it[LeaveAloneTable.endsAt] }
    }

    /** Retention: a request whose end has passed is read by nothing; answers how many went. */
    suspend fun pruneExpired(now: Instant): Int = dbTransaction {
        LeaveAloneTable.deleteWhere { LeaveAloneTable.endsAt lessEq now }
    }
}

private fun inChat(chat: ChatRef): Op<Boolean> =
    (LeaveAloneTable.platform eq chat.platform) and (LeaveAloneTable.chatId eq chat.id)

private fun forUser(chat: ChatRef, user: UserRef): Op<Boolean> =
    inChat(chat) and (LeaveAloneTable.userId eq user.id)
