package com.helltar.vusan.telegram.tools.chat

import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.telegram.api
import com.helltar.vusan.telegram.telegramChatId
import com.helltar.vusan.telegram.telegramUserId
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember
import org.telegram.telegrambots.meta.api.objects.ChatPermissions
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberAdministrator
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberMember
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberOwner
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberRestricted
import org.telegram.telegrambots.meta.generics.TelegramClient

/** A right the bot uses on a person's behalf, which that person must hold too. */
internal enum class ChatRight {
    CHANGE_INFO,

    /** Opening a topic, which a group may let every member do. */
    CREATE_TOPICS,

    /** Renaming, closing and reopening one, which only an admin may do to a topic they did not open. */
    MANAGE_TOPICS
}

/**
 * Refuses the call unless the person behind this turn could do it themselves. The bot is an admin, so without
 * this check any member could rename a group through it. Where the chat lets every member change its info,
 * every member may ask.
 */
internal suspend fun TelegramClient.requireSenderHolds(context: RequestContext, right: ChatRight) {
    // an anonymous admin or a channel stands for nobody whose rights can be read
    require(context.sender.isPerson) {
        "the message was not sent by a person, so nobody's rights back this request; do not do it"
    }

    val chatId = context.chatRef.telegramChatId
    val member = api { executeAsync(GetChatMember.builder().chatId(chatId).userId(context.user.telegramUserId).build()) }
    // only a non-admin is bound by what the chat is: its defaults, and whether it is public
    val chat =
        if (member is ChatMemberMember || member is ChatMemberRestricted) api { executeAsync(GetChat.builder().chatId(chatId).build()) }
        else null

    require(holds(member, chat?.permissions, right, isPublic = chat?.userName != null)) {
        "the person asking may not ${right.action} in this chat themselves, and the bot does not do for anyone " +
                "what they could not do; tell them so"
    }
}

/**
 * [defaults] are the chat's permissions for ordinary members. A member's `can_manage_topics` only opens topics,
 * and telegram ignores a member's `can_change_info` in a public group, where only admins change its info.
 */
internal fun holds(member: ChatMember, defaults: ChatPermissions?, right: ChatRight, isPublic: Boolean): Boolean =
    when (member) {
        is ChatMemberOwner -> true

        is ChatMemberAdministrator ->
            if (right == ChatRight.CHANGE_INFO) member.canChangeInfo == true else member.canManageTopics == true

        // a restriction replaces the chat's defaults rather than adding to them
        is ChatMemberRestricted ->
            member.isMember == true && right.heldByMember(member.canChangeInfo, member.canManageTopics, isPublic)

        is ChatMemberMember ->
            defaults != null &&
                right.heldByMember(defaults.canChangeInfo, defaults.canManageTopics ?: defaults.canPinMessages, isPublic)

        else -> false
    }

private fun ChatRight.heldByMember(changeInfo: Boolean?, createTopics: Boolean?, isPublic: Boolean): Boolean =
    when (this) {
        ChatRight.CHANGE_INFO -> !isPublic && changeInfo == true
        ChatRight.CREATE_TOPICS -> createTopics == true
        ChatRight.MANAGE_TOPICS -> false
    }

private val ChatRight.action: String
    get() =
        when (this) {
            ChatRight.CHANGE_INFO -> "change the chat's photo, title or description"
            ChatRight.CREATE_TOPICS -> "open forum topics"
            ChatRight.MANAGE_TOPICS -> "rename, close or reopen forum topics"
        }
