package com.helltar.vusan.telegram.callback

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.outbox.WidgetRequests
import com.helltar.vusan.request.AccessPolicy
import com.helltar.vusan.telegram.WidgetRecord
import com.helltar.vusan.telegram.WidgetRegistry
import com.helltar.vusan.telegram.delivery.answerWidgetQuery
import com.helltar.vusan.telegram.delivery.editMessageWidgetData
import com.helltar.vusan.telegram.delivery.editSharedWidgetData
import com.helltar.vusan.telegram.delivery.isMessageNotModified
import com.helltar.vusan.telegram.tallyAnswer
import com.helltar.vusan.telegram.telegramChat
import com.helltar.vusan.telegram.telegramUser
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Duration.Companion.seconds
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.api.objects.chat.Chat
import org.telegram.telegrambots.meta.api.objects.widget.SharedWidgetQuery
import org.telegram.telegrambots.meta.api.objects.widget.WidgetQuery
import org.telegram.telegrambots.meta.api.objects.widget.WidgetQueryType
import org.telegram.telegrambots.meta.api.objects.widget.WidgetQueryTypeSentTransaction
import org.telegram.telegrambots.meta.api.objects.widget.WidgetQueryTypeSignData
import org.telegram.telegrambots.meta.generics.TelegramClient

/**
 * A press inside a widget the bot sent: `widget_query`, or `shared_widget_query` from a forwarded copy of a
 * shared widget. A copy names no chat and no message, only the shared widget, so the bot never learns where it
 * was forwarded.
 */
internal data class WidgetPress(
    val id: String,
    val from: User,
    val data: String,
    // a TON wallet's signature or transaction rather than data the widget's code sent
    val fromWallet: Boolean,
    val chat: Chat? = null,
    val messageId: Int? = null,
    val widgetId: String? = null,
) {

    /** Pressed in a forwarded copy: no chat, no message, nothing but the shared widget and the person. */
    val fromCopy: Boolean
        get() = chat == null || messageId == null

    constructor(query: WidgetQuery) :
            this(query.id, query.from, query.data, query.type.isWallet, query.chat, query.messageId, query.widgetId)

    constructor(query: SharedWidgetQuery) :
            this(query.id, query.from, query.data, query.type.isWallet, widgetId = query.widgetId)
}

// a query type the library does not know reads as null, and is taken as data like the `data` type
private val WidgetQueryType?.isWallet: Boolean
    get() = this is WidgetQueryTypeSignData || this is WidgetQueryTypeSentTransaction

/**
 * Where a press inside one of the bot's widgets goes, by what the widget was sent for: a counting widget is
 * answered on the spot with the new counts, an `agent` widget becomes the presser's next turn, anything else
 * gets an empty answer. Every press is answered, since telegram shows the widget a timeout otherwise.
 */
internal class WidgetQueryHandler(
    private val client: TelegramClient,
    private val registry: WidgetRegistry,
    private val accessPolicy: AccessPolicy,
    // the presser's turn, answered under the widget: `AgentTurns.dispatchWidgetRequest`
    private val startTurn: suspend (chat: Chat, user: User, widget: WidgetRecord, data: String) -> Unit,
) {

    // one turn at a time per person and widget: a widget asking in a loop must not queue turns behind it
    private val turnsUnderWay = ConcurrentHashMap.newKeySet<String>()

    // counts waiting to be published, per widget, and who is publishing them; see [publish]
    private val unpublished = ConcurrentHashMap.newKeySet<String>()
    private val publishers = ConcurrentHashMap<String, Mutex>()

    suspend fun handle(query: WidgetPress) {
        val widget =
            runCatching { find(query) }.getOrElse {
                it.rethrowIfCancellation()
                log.warn(it) { "failed to look up the widget behind query data=[${query.data}] user=[${query.from.id}]" }
                null
            }

        // no widget of this bot asks a wallet for anything, so a wallet's answer has nowhere to go
        if (query.fromWallet) return answer(query, null)

        when (widget?.requests) {
            WidgetRequests.TALLY -> tally(query, widget)
            WidgetRequests.AGENT -> runTurn(query, widget)
            WidgetRequests.NONE, null -> answer(query, null)
        }
    }

    // a message's own row first: a shared widget posted again keeps its own chat and caption there
    private suspend fun find(query: WidgetPress): WidgetRecord? {
        val chat = query.chat
        val messageId = query.messageId

        if (chat != null && messageId != null) registry.find(chat.id, messageId)?.let { return it }

        return query.widgetId?.let { registry.origin(it) }
    }

    // counting costs nothing, so it is open to anyone who sees the widget, forwarded copies included
    private suspend fun tally(query: WidgetPress, widget: WidgetRecord) {
        val outcome = registry.tally(widget, query.from.id, query.data)

        answer(query, tallyAnswer(outcome.publicData, outcome.mine, outcome.refused))

        if (outcome.changed) publish(outcome.record)
    }

    private suspend fun runTurn(query: WidgetPress, widget: WidgetRecord) {
        val chat = query.chat

        // a forwarded copy has no chat to answer in, and a turn is paid for by the chat the widget lives in
        if (chat == null || query.fromCopy) return answer(query, null)

        if (!accessPolicy.allows(telegramChat(chat.id), telegramUser(query.from.id))) {
            log.warn { "denied widget query: chat=[${chat.id}] user=[${query.from.id}]" }
            return answer(query, null)
        }

        val key = "${chat.id}:${widget.messageId}:${query.from.id}"

        // the widget learns of the turn through its state, so the press itself needs nothing back
        answer(query, null)

        if (!turnsUnderWay.add(key)) {
            log.info { "widget query skipped, a turn from it is under way: chat=[${chat.id}] msg=[${widget.messageId}] user=[${query.from.id}]" }
            return
        }

        try {
            startTurn(chat, query.from, widget, query.data)
        } finally {
            turnsUnderWay.remove(key)
        }
    }

    /**
     * Writes the latest counts to telegram, at most once a second per widget, which is as often as telegram
     * takes a shared widget's edits. Presses arriving meanwhile only mark the widget; whoever holds its lock
     * publishes the newest counts once more, so a burst costs two edits rather than one per press.
     */
    private suspend fun publish(holder: WidgetRecord) {
        val key = "${holder.chatId}:${holder.messageId}"
        val mutex = publishers.computeIfAbsent(key) { Mutex() }

        unpublished += key

        while (key in unpublished && mutex.tryLock()) {
            try {
                while (unpublished.remove(key)) {
                    registry.find(holder.chatId, holder.messageId)?.publicData?.let { edit(holder, it) }
                    delay(PUBLISH_INTERVAL)
                }
            } finally {
                mutex.unlock()
            }
        }
    }

    private suspend fun edit(holder: WidgetRecord, publicData: String) {
        runCatching {
            if (holder.widgetId != null)
                editSharedWidgetData(client, holder.widgetId, publicData, cacheTimeSeconds = null)
            else
                editMessageWidgetData(client, holder.chatId, holder.messageId, chatData = null, publicData = publicData)
        }.onFailure {
            it.rethrowIfCancellation()
            if (!it.isMessageNotModified())
                log.warn(it) { "failed to publish counts of widget chat=[${holder.chatId}] msg=[${holder.messageId}]" }
        }
    }

    private suspend fun answer(query: WidgetPress, data: String?) {
        runCatching { answerWidgetQuery(client, query.id, data) }
            .onFailure {
                it.rethrowIfCancellation()
                log.warn(it) { "failed to answer widget query user=[${query.from.id}]" }
            }
    }

    private companion object {
        val PUBLISH_INTERVAL = 1.seconds

        val log = KotlinLogging.logger {}
    }
}
