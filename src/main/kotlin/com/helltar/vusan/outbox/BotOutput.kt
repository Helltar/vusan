package com.helltar.vusan.outbox

sealed class BotOutput {

    open val acceptsCaption: Boolean
        get() = false

    data class Text(val text: String) : BotOutput()

    // [originMessageId] is the user message the exchange started from. the selection returns as a callback
    // with no message of its own, so without it the follow-up would answer the bot's own question and
    // anything it schedules would lose the asking message.
    data class InlineChoice(
        val question: String,
        val options: List<String>,
        // the external id of the person the buttons are for; only they may press one.
        val ownerId: String,
        val historyRevision: Long,
        val originMessageId: String? = null,
    ) : BotOutput() {
        init {
            validateQuestionAndOptions("Inline choice", question, options)
            require(ownerId.isNotBlank()) { "Inline choice owner id must not be blank" }
            require(historyRevision >= 0L) { "Inline choice history revision must not be negative" }
            require(originMessageId == null || originMessageId.isNotBlank()) {
                "Inline choice origin message id must not be blank"
            }
        }
    }

    // an opt-in Bot API 10.1 rich message for large, structured replies (long comparisons, tables,
    // multi-section documents). [markdown] is github-flavored; delivery falls back to a .md document on
    // rejection. some third-party clients (e.g. telegram x) show rich messages as unsupported, so HTML [Text]
    // stays the default.
    data class RichMessage(val markdown: String) : BotOutput() {
        init {
            require(markdown.isNotBlank()) { "RichMessage markdown must not be blank" }
        }
    }

    // [spoiler] covers a photo, a video or an animation until someone taps it; no other kind has one.
    class Photo(val bytes: ByteArray, val filename: String, val spoiler: Boolean = false) : BotOutput() {
        override val acceptsCaption: Boolean get() = true
    }

    class PhotoGroup(val photos: List<Photo>) : BotOutput() {
        init {
            require(photos.size in 2..10) {
                "PhotoGroup requires 2..10 photos, got ${photos.size}"
            }
        }
    }

    class Document(val bytes: ByteArray, val filename: String) : BotOutput() {
        override val acceptsCaption: Boolean get() = true
    }

    class DocumentGroup(val documents: List<Document>) : BotOutput() {
        init {
            require(documents.size in 2..10) {
                "DocumentGroup requires 2..10 documents, got ${documents.size}"
            }
        }
    }

    class Animation(
        val url: String? = null,
        val bytes: ByteArray? = null,
        val filename: String = "animation.gif",
        val spoiler: Boolean = false,
    ) : BotOutput() {
        override val acceptsCaption: Boolean get() = true

        init {
            require((url != null) != (bytes != null)) {
                "Animation needs exactly one of url or bytes"
            }
        }
    }

    // sent by file_id from the sticker catalog, always alone since the Bot API takes no sticker caption.
    // [catalogId] lets a rejected file_id be reported back.
    data class Sticker(val fileId: String, val catalogId: Long) : BotOutput() {
        init {
            require(fileId.isNotBlank()) { "Sticker file id must not be blank" }
            require(catalogId > 0L) { "Sticker catalog id must be positive" }
        }
    }

    class Voice(
        val bytes: ByteArray,
        val durationSeconds: Int? = null,
    ) : BotOutput()

    class VideoNote(
        val bytes: ByteArray,
        val durationSeconds: Int? = null,
        val size: Int? = null,
    ) : BotOutput()

    class Video(
        val bytes: ByteArray,
        val filename: String,
        val durationSeconds: Int? = null,
        val width: Int? = null,
        val height: Int? = null,
        val thumbnail: ByteArray? = null,
        val sourceUrl: String? = null,
        val spoiler: Boolean = false,
    ) : BotOutput() {
        override val acceptsCaption: Boolean get() = true
    }

    class Audio(
        val bytes: ByteArray,
        val filename: String,
        val title: String,
        val performer: String,
        val durationSeconds: Int? = null,
        val trackUrl: String? = null,
    ) : BotOutput() {
        override val acceptsCaption: Boolean get() = true
    }

    class AudioGroup(val audios: List<Audio>) : BotOutput() {
        init {
            require(audios.size in 2..10) {
                "AudioGroup requires 2..10 audios, got ${audios.size}"
            }
        }
    }

    data class Quiz(
        val question: String,
        val options: List<String>,
        val correctOptionIndex: Int,
        val explanation: String? = null,
        val isAnonymous: Boolean = false,
    ) : BotOutput() {
        init {
            validateQuestionAndOptions("Quiz", question, options)

            require(correctOptionIndex in options.indices) {
                "Correct option index must point to one of the provided options"
            }

            explanation?.let {
                require(it.length <= MAX_EXPLANATION_LENGTH) {
                    "Quiz explanation must be at most $MAX_EXPLANATION_LENGTH characters"
                }
            }
        }
    }

    data class Poll(
        val question: String,
        val options: List<String>,
        val isAnonymous: Boolean = true,
        val allowsMultipleAnswers: Boolean = false,
    ) : BotOutput() {
        init {
            validateQuestionAndOptions("Poll", question, options)
        }
    }

    /**
     * An HTML widget drawn inside the message, with the state only the bot writes ([chatData] for this chat,
     * [publicData] wherever it is shown) and what a press inside it does. [caption] is what a client without
     * widgets shows instead, and the chat list. [shared] makes one shared state for every message showing it.
     */
    class Widget(
        val html: String,
        val caption: String,
        val requests: WidgetRequests = WidgetRequests.NONE,
        val shared: Boolean = false,
        val chatData: String? = null,
        val publicData: String? = null,
        val width: Int = 0,
        val height: Int = 0,
    ) : BotOutput() {
        init {
            require(html.isNotBlank()) { "Widget html must not be blank" }
            require(caption.isNotBlank()) { "Widget caption must not be blank" }
            require(width >= 0 && height >= 0) { "Widget size anchors must not be negative" }
        }
    }

    /** A shared widget sent again, by telegram's id for it: the same HTML and shared state, this chat's own state. */
    data class SharedWidget(
        val widgetId: String,
        val caption: String,
        val chatData: String? = null,
    ) : BotOutput() {
        init {
            require(widgetId.isNotBlank()) { "Shared widget id must not be blank" }
            require(caption.isNotBlank()) { "Shared widget caption must not be blank" }
        }
    }

    data class Reaction(val messageId: String, val emoji: String) : BotOutput() {
        init {
            require(messageId.isNotBlank()) { "Reaction message id must not be blank" }
            require(emoji.isNotBlank()) { "Reaction emoji must not be blank" }
        }
    }
}

/** What a press inside a widget does. */
enum class WidgetRequests {

    /** Nothing: the widget never asks the bot. */
    NONE,

    /** A press starts a turn of the agent, which answers by changing the widget's state. */
    AGENT,

    /** The bot keeps each person's latest choice and publishes the counts as the shared state, no turn involved. */
    TALLY,
}
