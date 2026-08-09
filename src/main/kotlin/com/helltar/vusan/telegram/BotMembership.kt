package com.helltar.vusan.telegram

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.tasks.TasksRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberBanned
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberLeft
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberRestricted
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberUpdated

private val log = KotlinLogging.logger("BotMembership")

// whether this membership still lets the bot write into the chat at all. `restricted` is the one that
// has to be read rather than matched: it covers everything from "cannot send photos" to "cannot send
// anything", and only the latter makes a scheduled task there pointless.
internal fun ChatMember.allowsPosting(): Boolean = when (this) {
    is ChatMemberLeft, is ChatMemberBanned -> false
    is ChatMemberRestricted -> canSendMessages == true
    else -> true
}

/**
 * Telegram announces every change to the bot's own membership, in groups and private chats alike, so
 * losing the right to post is known the moment it happens instead of at the next scheduled fire — which
 * would run a whole agent turn first and only then fail at delivery. Tasks are paused rather than
 * deleted, so their owners can resume them from `/tasks` if the bot gets back in.
 *
 * A removal that happens while the bot is down past Telegram's update retention is never announced, so
 * this is the cheap path and not the only one: `TaskScheduler` parks the same chat when a fire turns out
 * to be undeliverable.
 */
internal suspend fun parkTasksOnLostAccess(tasks: TasksRepository, membership: ChatMemberUpdated) {
    val status = membership.newChatMember
    if (status.allowsPosting()) return

    val chatId = membership.chat.id

    runCatching { tasks.pauseAllInChat(chatId) }
        .onFailure { error ->
            error.rethrowIfCancellation()
            log.error(error) { "failed to park scheduled tasks of chat=$chatId" }
        }
        .onSuccess { paused ->
            log.info {
                "bot can no longer post in chat=$chatId (status=${status.status}); " +
                        "paused $paused scheduled task(s) there"
            }
        }
}
