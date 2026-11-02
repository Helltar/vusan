package com.helltar.vusan.telegram.tools.chat

import com.helltar.vusan.delivery.Destination
import com.helltar.vusan.delivery.OutputDelivery
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.telegram.api
import com.helltar.vusan.telegram.telegramChatId
import com.helltar.vusan.telegram.telegramThreadId
import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard
import org.telegram.telegrambots.meta.api.methods.forum.CloseForumTopic
import org.telegram.telegrambots.meta.api.methods.forum.CreateForumTopic
import org.telegram.telegrambots.meta.api.methods.forum.EditForumTopic
import org.telegram.telegrambots.meta.api.methods.forum.ReopenForumTopic
import org.telegram.telegrambots.meta.generics.TelegramClient

internal const val MAX_TOPIC_NAME_CHARS = 128

/**
 * Opens, renames, closes and reopens forum topics. Offered only in a forum where the bot is an admin with the
 * right to, and each call also needs the person asking to hold that right; see [requireSenderHolds]. Deleting
 * a topic is left out on purpose: it takes every message in it along, past undoing.
 *
 * A first message in a new topic goes through [delivery] as something the bot says on its own, since the
 * turn's own answer belongs to the topic it was asked in.
 */
class ForumTopicTools(
    private val telegram: TelegramClient,
    private val delivery: OutputDelivery,
    private val context: RequestContext,
) : ToolSet {

    private val chatId: Long
        get() = context.chatRef.telegramChatId

    @Tool(ForumTopicToolDescriptions.CREATE_TOPIC)
    suspend fun createTopic(
        @Arg(ForumTopicToolDescriptions.TOPIC_NAME)
        name: String,
        @Arg(ForumTopicToolDescriptions.TOPIC_MESSAGE)
        message: String = "",
        @Arg(ForumTopicToolDescriptions.TOPIC_COLOR)
        color: String = "",
    ): String = suspendToolGuard {
        val topicName = name.requireToolText("Topic name", MAX_TOPIC_NAME_CHARS)
        val iconColor = topicIconColor(color)
        val firstMessage = message.trim()
        require(firstMessage.length <= MAX_MESSAGE_CHARS) { "Message must be at most $MAX_MESSAGE_CHARS characters" }
        telegram.requireSenderHolds(context, ChatRight.CREATE_TOPICS)

        val topic =
            telegram.api {
                executeAsync(CreateForumTopic.builder().chatId(chatId).name(topicName).iconColor(iconColor).build())
            }

        if (firstMessage.isNotEmpty()) {
            delivery.deliverUnprompted(
                Destination(context.chatRef, topic.messageThreadId.toString()),
                listOf(BotOutput.Text(firstMessage)),
            )
        }

        val posted = if (firstMessage.isEmpty()) "" else " with your first message in it"

        "Topic \"$topicName\" is open$posted; its id is ${topic.messageThreadId}."
    }

    @Tool(ForumTopicToolDescriptions.RENAME_TOPIC)
    suspend fun renameTopic(
        @Arg(ForumTopicToolDescriptions.NEW_TOPIC_NAME)
        name: String,
        @Arg(ForumTopicToolDescriptions.TOPIC_ID)
        topicId: Int? = null,
    ): String = suspendToolGuard {
        val topicName = name.requireToolText("Topic name", MAX_TOPIC_NAME_CHARS)
        val threadId = targetTopic(topicId)
        telegram.requireSenderHolds(context, ChatRight.MANAGE_TOPICS)

        telegram.api {
            executeAsync(EditForumTopic.builder().chatId(chatId).messageThreadId(threadId).name(topicName).build())
        }

        "The topic is now called \"$topicName\"."
    }

    @Tool(ForumTopicToolDescriptions.CLOSE_TOPIC)
    suspend fun closeTopic(
        @Arg(ForumTopicToolDescriptions.TOPIC_ID)
        topicId: Int? = null,
    ): String = suspendToolGuard {
        val threadId = targetTopic(topicId)
        telegram.requireSenderHolds(context, ChatRight.MANAGE_TOPICS)

        telegram.api { executeAsync(CloseForumTopic.builder().chatId(chatId).messageThreadId(threadId).build()) }

        "The topic is closed."
    }

    @Tool(ForumTopicToolDescriptions.REOPEN_TOPIC)
    suspend fun reopenTopic(
        @Arg(ForumTopicToolDescriptions.TOPIC_ID)
        topicId: Int? = null,
    ): String = suspendToolGuard {
        val threadId = targetTopic(topicId)
        telegram.requireSenderHolds(context, ChatRight.MANAGE_TOPICS)

        telegram.api { executeAsync(ReopenForumTopic.builder().chatId(chatId).messageThreadId(threadId).build()) }

        "The topic is open again."
    }

    // the General topic has no id and its own methods, which these tools do not offer
    private fun targetTopic(topicId: Int?): Int =
        requireNotNull(topicId ?: telegramThreadId(context.chat.threadId)) {
            "this conversation is in the group's General topic, which cannot be renamed, closed or reopened " +
                    "this way; name a topic `createTopic` opened"
        }

    private companion object {
        // telegram's cap on one text message
        const val MAX_MESSAGE_CHARS = 4096
    }
}

/** The color a topic icon takes by name; Telegram accepts only these six. */
internal fun topicIconColor(name: String): Int? {
    val key = name.trim().lowercase()

    if (key.isEmpty()) return null

    return requireNotNull(TOPIC_ICON_COLORS[key]) {
        "unknown topic color [$key]; use one of ${TOPIC_ICON_COLORS.keys.joinToString { "`$it`" }}"
    }
}

private val TOPIC_ICON_COLORS =
    mapOf(
        "blue" to 0x6FB9F0,
        "yellow" to 0xFFD67E,
        "violet" to 0xCB86DB,
        "green" to 0x8EEE98,
        "rose" to 0xFF93B2,
        "red" to 0xFB6F5F,
    )
