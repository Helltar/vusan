package com.helltar.vusan.telegram.tools.chat

import com.helltar.vusan.delivery.DeliveryOutcome
import com.helltar.vusan.delivery.Destination
import com.helltar.vusan.delivery.OutputDelivery
import com.helltar.vusan.delivery.TurnDelivery
import com.helltar.vusan.outbox.BotOutput
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import org.telegram.telegrambots.meta.api.methods.forum.CreateForumTopic
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember
import org.telegram.telegrambots.meta.api.objects.ChatPermissions
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.api.objects.chat.ChatFullInfo
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberMember
import org.telegram.telegrambots.meta.api.objects.forum.ForumTopic
import org.telegram.telegrambots.meta.generics.TelegramClient

internal val person: User = User.builder().id(7L).isBot(false).firstName("Ann").build()

/**
 * A chat where the person asking is [member] and ordinary members hold [defaults], public when it has a
 * [username]; every other request is recorded in [changes] and succeeds, a new topic getting [NEW_TOPIC_ID].
 */
internal class FakeChat(
    private val member: ChatMember = ChatMemberMember.builder().user(person).build(),
    private val defaults: ChatPermissions? = ChatPermissions.builder().canChangeInfo(true).canManageTopics(true).build(),
    private val username: String? = null,
) {

    val changes = mutableListOf<Any>()

    val client: TelegramClient =
        Proxy.newProxyInstance(TelegramClient::class.java.classLoader, arrayOf(TelegramClient::class.java)) { _, method, args ->
            check(method.name == "executeAsync") { "unexpected call: ${method.name}" }

            val answer: Any =
                when (val request = args.single()) {
                    is GetChatMember -> member
                    is GetChat ->
                        ChatFullInfo.builder().id(-100L).type("supergroup").accentColorId(0).permissions(defaults).userName(username).build()

                    is CreateForumTopic -> {
                        changes += request
                        ForumTopic().apply {
                            messageThreadId = NEW_TOPIC_ID
                            name = request.name
                        }
                    }

                    else -> {
                        changes += request
                        true
                    }
                }

            CompletableFuture.completedFuture(answer)
        } as TelegramClient

    companion object {
        const val NEW_TOPIC_ID = 55
    }
}

internal class RecordingDelivery : OutputDelivery {

    val unprompted = mutableListOf<Pair<Destination, List<BotOutput>>>()

    override suspend fun deliver(delivery: TurnDelivery): DeliveryOutcome = error("not a turn")

    override suspend fun deliverUnprompted(destination: Destination, outputs: List<BotOutput>, anchorMessageId: String?): DeliveryOutcome {
        unprompted += destination to outputs

        return DeliveryOutcome.Handled
    }

    override suspend fun notify(destination: Destination, text: String): DeliveryOutcome = error("not a notice")
}
