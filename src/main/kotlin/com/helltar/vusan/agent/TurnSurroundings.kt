package com.helltar.vusan.agent

import com.helltar.vusan.agent.grouplog.GroupLogEntry
import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.agent.grouplog.renderGroupLog
import com.helltar.vusan.agent.grouplog.withoutExchangesWith
import com.helltar.vusan.agent.presence.LeaveAloneRepository
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.request.RequestContext
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// enough to follow a question without a subject, bounded so it stays a glance rather than a transcript.
private const val RECENT_CHAT_MESSAGES = 15

/**
 * What a turn sees of its chat beyond the request and its own history: the group's diary, what the group was
 * just saying, and the sticker shortlist. Each is read fresh and only where it applies: a private chat has
 * its history instead of a diary or recent chat, and a shortlist only where a sticker can be sent.
 *
 * The diary and shortlist come as functions: the sticker catalog resends by `file_id`, one messenger's model,
 * and nothing here should need a client. The sender's leave-alone request is read here too, since it changes
 * what the turn is told about the person.
 */
class TurnSurroundings(
    private val groupLog: GroupLogRepository? = null,
    // what the bot wrote about this chat's last few days, if the deployment keeps a diary.
    private val diary: (suspend (ChatRef) -> String?)? = null,
    // the chat's sticker shortlist, if the deployment has one.
    private val stickerCatalog: (suspend (ChatRef) -> String?)? = null,
    // who asked not to be written to, and until when
    private val leaveAlone: LeaveAloneRepository? = null,
) {

    // a private chat has unasked lines too, the follow-ups; a shared account is nobody in particular to leave
    // alone
    suspend fun leftAloneUntil(context: RequestContext): Instant? {
        val requests = leaveAlone?.takeIf { context.sender.isPerson } ?: return null

        return try {
            requests.activeUntil(context.chatRef, context.user, Instant.now())
        } catch (e: Throwable) {
            e.rethrowIfCancellation()
            log.warn(e) { "failed to read the leave-alone request for chat=${context.chat.id} user=${context.sender.id}" }
            null
        }
    }

    // a private chat has its history instead of a diary.
    suspend fun diaryFor(context: RequestContext): String? {
        val entries = diary?.takeIf { !context.chat.isPrivate } ?: return null

        return try {
            entries(context.chatRef)
        } catch (e: Throwable) {
            e.rethrowIfCancellation()
            log.warn(e) { "failed to load the diary for chat=${context.chat.id}" }
            null
        }
    }

    // in a group the bot sees only messages addressed to it, so without this "and what do you think?" arrives
    // without a subject. the triggering message is left out, since the model sees it as the request.
    suspend fun recentChatFor(context: RequestContext): String? {
        val repository = groupLog?.takeIf { !context.chat.isPrivate } ?: return null

        val entries =
            try {
                repository.recent(
                    chat = context.chatRef,
                    // over-fetch, so dropping this user's own exchanges below does not thin the slice.
                    limit = RECENT_CHAT_MESSAGES * RECENT_CHAT_OVERFETCH,
                    since = Instant.now().minus(RECENT_CHAT_MINUTES, ChronoUnit.MINUTES),
                    // an ambient slice is cut at the message instead, so it has to be there.
                    excludeMessageId = context.messageId.takeUnless { context.ambient },
                )
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                log.warn(e) { "failed to load the recent chat slice for chat=${context.chat.id}" }
                return null
            }

        val recent = recentChatSlice(entries, context.sender.id, context.messageId, context.ambient)

        return renderGroupLog(recent, ZoneId.systemDefault(), RECENT_CHAT_LINE_CHARS, RECENT_CHAT_MAX_CHARS)
            .text
            .takeIf { it.isNotBlank() }
    }

    // only where the reply can carry a sticker: a group forbidding them keeps StickerTools out, so the
    // shortlist would have no tool to send it.
    suspend fun stickerCatalogFor(context: RequestContext): String? {
        val catalog = stickerCatalog?.takeIf { context.chat.capabilities.stickersAndAnimations } ?: return null

        return try {
            catalog(context.chatRef)
        } catch (e: Throwable) {
            e.rethrowIfCancellation()
            log.warn(e) { "failed to load the sticker shortlist for chat=${context.chat.id}" }
            null
        }
    }

    private companion object {
        val log = KotlinLogging.logger {}

        // `<recent_chat>` rides on every group turn, so it is sized for cheapness: enough to know the topic,
        // never enough to answer a recap on its own.
        const val RECENT_CHAT_MAX_CHARS = 1_000
        const val RECENT_CHAT_LINE_CHARS = 120
        const val RECENT_CHAT_OVERFETCH = 3
        const val RECENT_CHAT_MINUTES = 60L
    }
}

/**
 * What `<recent_chat>` shows of [entries], oldest first.
 *
 * An ordinary turn leaves out the person's own exchanges with the bot, which it replays as history; [entries]
 * already lack the message itself. An ambient turn keeps those exchanges and ends right before the message:
 * only line order tells whether a bare "which one?" follows the bot's reply or someone else's line, and
 * history has no such order. A message the log has not recorded yet leaves the slice uncut.
 */
internal fun recentChatSlice(
    entries: List<GroupLogEntry>,
    senderId: String,
    messageId: String?,
    ambient: Boolean,
): List<GroupLogEntry> {
    if (!ambient) return entries.withoutExchangesWith(senderId).takeLast(RECENT_CHAT_MESSAGES)

    val at = entries.indexOfFirst { messageId != null && it.messageId == messageId }
    val before = if (at >= 0) entries.take(at) else entries

    return before.takeLast(RECENT_CHAT_MESSAGES)
}
