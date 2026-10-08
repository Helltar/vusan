package com.helltar.vusan.agent

import com.helltar.vusan.agent.grouplog.GroupLogEntry
import com.helltar.vusan.agent.grouplog.GroupLogRepository
import com.helltar.vusan.agent.grouplog.renderGroupLog
import com.helltar.vusan.agent.grouplog.withoutExchangesWith
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.request.ChatRef
import com.helltar.vusan.request.RequestContext
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// the slice of what the group was just saying that a turn carries: enough to follow a question with no
// subject, bounded so it stays a glance rather than a transcript.
private const val RECENT_CHAT_MESSAGES = 15

/**
 * What a turn is shown of the chat it happens in, beyond the request and the conversation's own
 * history: the group's diary, what the group was just saying, and the chat's sticker shortlist. Each is
 * read fresh for the turn, and each only where it applies — a private chat has its history instead of a
 * diary or a recent chat, and a shortlist is offered only where a sticker can be sent.
 *
 * The diary and the shortlist come as functions rather than as their sources: the sticker catalog
 * resends by `file_id`, which is one messenger's own model, and nothing on this side has business
 * holding something that needs a client to exist.
 */
class TurnSurroundings(
    private val groupLog: GroupLogRepository? = null,
    // what the bot wrote down about this chat's last few days, if this deployment keeps a diary.
    private val diary: (suspend (ChatRef) -> String?)? = null,
    // what the chat's sticker shortlist looks like, if this deployment has one.
    private val stickerCatalog: (suspend (ChatRef) -> String?)? = null,
) {

    // a group's days belong to the group: a private chat has its history instead, and no diary.
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

    // what the group was saying just before this turn. in a group the bot only ever sees the messages
    // addressed to it, so without this a question like "and what do you think?" arrives with no subject.
    // the triggering message is left out — the model is already being shown it as the request itself.
    suspend fun recentChatFor(context: RequestContext): String? {
        val repository = groupLog?.takeIf { !context.chat.isPrivate } ?: return null

        val entries =
            try {
                repository.recent(
                    chat = context.chatRef,
                    // over-fetch: dropping this user's own exchanges below must not thin the slice out.
                    limit = RECENT_CHAT_MESSAGES * RECENT_CHAT_OVERFETCH,
                    since = Instant.now().minus(RECENT_CHAT_MINUTES, ChronoUnit.MINUTES),
                    // an ambient slice is cut at the message instead, so it has to be there to cut at.
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

    // the catalog is worth its tokens only where the reply can actually carry a sticker: a group that
    // forbids them keeps StickerTools out of the catalog, so an index here would offer the model a
    // shortlist it has no tool to send.
    suspend fun stickerCatalogFor(context: RequestContext): String? =
        stickerCatalog
            ?.takeIf { context.chat.capabilities.stickersAndAnimations }
            ?.invoke(context.chatRef)

    private companion object {
        val log = KotlinLogging.logger {}

        // `<recent_chat>` rides along on every group turn, so it is budgeted for cheapness, not for
        // detail: enough to know what is being talked about, never enough to answer a recap question on
        // its own.
        const val RECENT_CHAT_MAX_CHARS = 1_000
        const val RECENT_CHAT_LINE_CHARS = 120
        const val RECENT_CHAT_OVERFETCH = 3
        const val RECENT_CHAT_MINUTES = 60L
    }
}

/**
 * What `<recent_chat>` shows of [entries], oldest first.
 *
 * An ordinary turn leaves out the person's own exchanges with the bot, which it replays as history, and
 * [entries] already lack the message itself. A turn nobody called the bot into keeps those exchanges and
 * ends right before the message: nothing but the order of the lines says whether a bare "which one?"
 * follows the bot's last reply or somebody else's line in between, and history carries no such order.
 * A message the log has not recorded yet leaves the slice uncut.
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
