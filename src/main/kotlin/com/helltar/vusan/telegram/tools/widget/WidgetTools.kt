package com.helltar.vusan.telegram.tools.widget

import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.WidgetRequests
import com.helltar.vusan.request.RequestContext
import com.helltar.vusan.telegram.WidgetRecord
import com.helltar.vusan.telegram.WidgetRegistry
import com.helltar.vusan.telegram.delivery.editMessageWidgetData
import com.helltar.vusan.telegram.delivery.editSharedWidgetData
import com.helltar.vusan.telegram.delivery.isMessageNotModified
import com.helltar.vusan.telegram.delivery.telegramRefusal
import com.helltar.vusan.telegram.tallyPublicData
import com.helltar.vusan.telegram.telegramChatId
import com.helltar.vusan.telegram.telegramThreadId
import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolFailure
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard
import java.util.zip.Deflater
import org.telegram.telegrambots.meta.generics.TelegramClient

// telegram's limits, as its server checks them: the html as sent and after zlib at level 9, each state field
internal const val MAX_WIDGET_HTML_BYTES = 32_768
internal const val MAX_WIDGET_COMPRESSED_BYTES = 4_096
internal const val MAX_WIDGET_STATE_BYTES = 1_024
internal const val MIN_WIDGET_REFRESH_SECONDS = 5
internal const val MAX_WIDGET_REFRESH_SECONDS = 3_600

private const val MAX_CAPTION_CHARS = 1_024
private const val MAX_SIZE_ANCHOR_PX = 2_000
private const val LISTED_WIDGETS = 10
private const val LISTED_STATE_CHARS = 300

/**
 * Telegram's HTML widgets: one sent with the reply, its state changed later, a shared one posted again, and
 * the chat's widgets listed. Only telegram has them.
 */
class WidgetTools(
    private val client: TelegramClient,
    private val registry: WidgetRegistry,
    private val context: RequestContext,
    private val outbox: BotOutbox,
) : ToolSet {

    private val chatId: Long
        get() = context.chatRef.telegramChatId

    @Tool(WidgetToolDescriptions.WIDGET_GUIDE, readOnly = true)
    suspend fun widgetGuide(): String = suspendToolGuard { guide }

    @Tool(WidgetToolDescriptions.SEND_WIDGET)
    suspend fun sendWidget(
        @Arg(WidgetToolDescriptions.HTML, takesReference = true)
        html: String,
        @Arg(WidgetToolDescriptions.CAPTION)
        caption: String,
        @Arg(WidgetToolDescriptions.REQUESTS)
        requests: String = "none",
        @Arg(WidgetToolDescriptions.SHARED)
        shared: Boolean = false,
        @Arg(WidgetToolDescriptions.CHAT_DATA)
        chatData: String = "",
        @Arg(WidgetToolDescriptions.PUBLIC_DATA)
        publicData: String = "",
        @Arg(WidgetToolDescriptions.WIDTH)
        width: Int = 0,
        @Arg(WidgetToolDescriptions.HEIGHT)
        height: Int = 0,
    ): String = suspendToolGuard {
        requireWidgetsHere()

        val page = html.trim()
        val compressed = checkWidgetSize(page)
        val text = caption.requireToolText("caption", MAX_CAPTION_CHARS)
        val mode = parseRequests(requests)

        require(width in 0..MAX_SIZE_ANCHOR_PX && height in 0..MAX_SIZE_ANCHOR_PX) {
            "width and height must be between 0 and $MAX_SIZE_ANCHOR_PX"
        }

        val firstChatData = chatData.takeIf { it.isNotBlank() }?.also { requireState("chatData", it) }
        val firstPublicData =
            publicData.takeIf { it.isNotBlank() }?.also { requireState("publicData", it) }
                ?: tallyPublicData(revision = 0, choices = emptyList()).takeIf { mode == WidgetRequests.TALLY }

        val widget = BotOutput.Widget(page, text, mode, shared, firstChatData, firstPublicData, width, height)

        if (!outbox.enqueue(widget))
            return@suspendToolGuard "This chat does not accept widgets (telegram checks them as photos), so nothing was sent. Answer another way."

        buildString {
            append("The widget will be sent with your reply ($compressed of $MAX_WIDGET_COMPRESSED_BYTES bytes compressed).")
            if (mode == WidgetRequests.AGENT) append(" Presses in it reach you as `<widget_request>`; answer them with `setWidgetState`.")
        }
    }

    @Tool(WidgetToolDescriptions.SET_WIDGET_STATE)
    suspend fun setWidgetState(
        @Arg(WidgetToolDescriptions.STATE_MESSAGE_ID)
        messageId: Int,
        @Arg(WidgetToolDescriptions.NEW_CHAT_DATA)
        chatData: String? = null,
        @Arg(WidgetToolDescriptions.NEW_PUBLIC_DATA)
        publicData: String? = null,
        @Arg(WidgetToolDescriptions.REFRESH_SECONDS)
        refreshSeconds: Int? = null,
    ): String = suspendToolGuard {
        val record = ownWidget(messageId)

        require(chatData != null || publicData != null || refreshSeconds != null) {
            "Pass `chatData`, `publicData` or `refreshSeconds`: nothing to change"
        }

        chatData?.let { requireState("chatData", it) }
        publicData?.let { requireState("publicData", it) }

        require(publicData == null || record.requests != WidgetRequests.TALLY) {
            "A `tally` widget's shared state is its counts, which only presses change; change its `chatData` instead"
        }

        refreshSeconds?.let {
            require(record.widgetId != null) { "Only a shared widget has a refresh period" }
            require(it in MIN_WIDGET_REFRESH_SECONDS..MAX_WIDGET_REFRESH_SECONDS) {
                "refreshSeconds must be between $MIN_WIDGET_REFRESH_SECONDS and $MAX_WIDGET_REFRESH_SECONDS"
            }
        }

        if (chatData != null || publicData != null)
            telegramCall { editMessageWidgetData(client, chatId, messageId, chatData, publicData) }

        if (refreshSeconds != null && record.widgetId != null)
            telegramCall { editSharedWidgetData(client, record.widgetId, publicData ?: record.publicData.orEmpty(), refreshSeconds) }

        registry.recordState(record, chatData, publicData)

        "The widget shows the new state; nothing was posted in the chat."
    }

    @Tool(WidgetToolDescriptions.SEND_WIDGET_AGAIN)
    suspend fun sendWidgetAgain(
        @Arg(WidgetToolDescriptions.AGAIN_MESSAGE_ID)
        messageId: Int,
        @Arg(WidgetToolDescriptions.AGAIN_CAPTION)
        caption: String = "",
        @Arg(WidgetToolDescriptions.AGAIN_CHAT_DATA)
        chatData: String = "",
    ): String = suspendToolGuard {
        requireWidgetsHere()

        val record = ownWidget(messageId)
        val widgetId = requireNotNull(record.widgetId) { "Widget $messageId is not shared, so it cannot be posted again; send a new one" }
        val text = caption.trim().ifEmpty { record.caption }.requireToolText("caption", MAX_CAPTION_CHARS)
        val state = chatData.takeIf { it.isNotBlank() }?.also { requireState("chatData", it) }

        if (!outbox.enqueue(BotOutput.SharedWidget(widgetId, text, state)))
            return@suspendToolGuard "This chat does not accept widgets (telegram checks them as photos), so nothing was sent. Answer another way."

        "Widget $messageId will be posted again with your reply."
    }

    @Tool(WidgetToolDescriptions.LIST_WIDGETS, readOnly = true)
    suspend fun listWidgets(): String = suspendToolGuard {
        val widgets = registry.recent(chatId, telegramThreadId(context.chat.threadId), LISTED_WIDGETS)

        if (widgets.isEmpty()) return@suspendToolGuard "You have no widgets in this chat."

        widgets.joinToString("\n", prefix = "Your latest widgets here, newest first:\n") { it.listLine() }
    }

    // telegram takes a bot's widget in a private chat only so far, so a group's turn sends it to the person instead
    private fun requireWidgetsHere() {
        require(context.chat.isPrivate || outbox.redirectToPrivate) {
            "Telegram does not take a bot's widget in a group yet. Call `replyInPrivateMessages` first to send it to the person's private chat, or answer another way"
        }
    }

    private suspend fun ownWidget(messageId: Int): WidgetRecord =
        requireNotNull(registry.find(chatId, messageId)) {
            "No widget of yours with message id $messageId in this chat; `listWidgets` lists them"
        }

    private companion object {
        val guide: String by lazy {
            requireNotNull(WidgetTools::class.java.getResource("/widget-guide.md")) { "widget-guide.md is missing" }.readText()
        }
    }
}

/** Checks [html] against telegram's two size limits and returns its compressed size, which the server counts. */
internal fun checkWidgetSize(html: String): Int {
    require(html.isNotEmpty()) { "html must not be empty" }
    require('\u0000' !in html) { "html must not contain NUL characters" }

    val bytes = html.toByteArray()

    require(bytes.size <= MAX_WIDGET_HTML_BYTES) {
        "The widget is ${bytes.size} bytes of HTML, over telegram's $MAX_WIDGET_HTML_BYTES. Write a smaller one"
    }

    val compressed = zlibSize(bytes)

    require(compressed <= MAX_WIDGET_COMPRESSED_BYTES) {
        "The widget is $compressed bytes after compression, over telegram's $MAX_WIDGET_COMPRESSED_BYTES (${bytes.size} bytes of HTML). " +
                "Cut comments, repeated inline styles, data: images and long text, then send it again"
    }

    return compressed
}

// what PHP's gzcompress($html, 9) produces, which telegram measures: a zlib stream at level 9
internal fun zlibSize(bytes: ByteArray): Int {
    val deflater = Deflater(Deflater.BEST_COMPRESSION)

    try {
        deflater.setInput(bytes)
        deflater.finish()

        val buffer = ByteArray(bytes.size + 64)
        var total = 0

        while (!deflater.finished()) total += deflater.deflate(buffer)

        return total
    } finally {
        deflater.end()
    }
}

private fun parseRequests(value: String): WidgetRequests =
    requireNotNull(WidgetRequests.entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }) {
        "requests must be `none`, `tally` or `agent`, not [$value]"
    }

private fun requireState(label: String, value: String) {
    require('\u0000' !in value) { "$label must not contain NUL characters" }

    val size = value.toByteArray().size

    require(size <= MAX_WIDGET_STATE_BYTES) { "$label is $size bytes, over telegram's $MAX_WIDGET_STATE_BYTES" }
}

// a refusal reaches the model in telegram's words; an edit that changes nothing already shows what was asked
private suspend fun telegramCall(call: suspend () -> Unit) {
    runCatching { call() }.onFailure { error ->
        error.rethrowIfCancellation()

        if (!error.isMessageNotModified())
            throw ToolFailure("Telegram refused the change: ${error.telegramRefusal ?: error.message}")
    }
}

private fun WidgetRecord.listLine(): String =
    buildString {
        append("- message $messageId: \"${caption.collapseWhitespaceAndCap(LISTED_STATE_CHARS)}\"")
        append(", requests=`${requests.name.lowercase()}`")
        if (widgetId != null) append(", shared")
        chatData?.let { append("\n  chatData: ${it.collapseWhitespaceAndCap(LISTED_STATE_CHARS)}") }
        publicData?.let { append("\n  publicData: ${it.collapseWhitespaceAndCap(LISTED_STATE_CHARS)}") }
    }
