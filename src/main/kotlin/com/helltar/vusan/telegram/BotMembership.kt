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

// whether the bot can still write here at all. `restricted` must be read, not matched: it ranges from "cannot
// send photos" to "cannot send anything", and only the latter makes a task here pointless.
internal fun ChatMember.allowsPosting(): Boolean = when (this) {
    is ChatMemberLeft, is ChatMemberBanned -> false
    is ChatMemberRestricted -> canSendMessages == true
    else -> true
}

/**
 * Telegram announces every change to the bot's own membership, so losing the right to post is known at once
 * instead of at the next fire, which would run a whole turn before failing at delivery. Tasks are paused, not
 * deleted, so owners can resume them from `/tasks` if the bot gets back in.
 *
 * A removal while the bot was down past telegram's update retention is never announced, so this is the cheap
 * path, not the only one: `TaskScheduler` parks a chat when a fire proves undeliverable.
 */
internal suspend fun parkTasksOnLostAccess(tasks: TasksRepository, membership: ChatMemberUpdated) {
    val status = membership.newChatMember
    if (status.allowsPosting()) return

    val chat = telegramChat(membership.chat.id)

    runCatching { tasks.pauseAllInChat(chat) }
        .onFailure { error ->
            error.rethrowIfCancellation()
            log.error(error) { "failed to park scheduled tasks of chat=[$chat]" }
        }
        .onSuccess { paused ->
            log.info {
                "bot can no longer post in chat=[$chat] (status=${status.status}); " +
                        "paused $paused scheduled task(s) there"
            }
        }
}
