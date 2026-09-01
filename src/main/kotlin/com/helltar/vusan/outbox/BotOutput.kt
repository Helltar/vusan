package com.helltar.vusan.outbox

sealed class BotOutput {

    open val acceptsCaption: Boolean
        get() = false

    data class Text(val text: String) : BotOutput()

    // [originMessageId] is the user message this exchange started from. the selection comes back as a
    // callback with no message of its own, so without it the follow-up turn would answer the bot's own
    // question and anything it schedules would lose the message that asked for it.
    data class InlineChoice(
        val question: String,
        val options: List<String>,
        val ownerId: Long,
        val historyRevision: Long,
        val originMessageId: Long? = null
    ) : BotOutput() {
        init {
            validateQuestionAndOptions("Inline choice", question, options)
            require(ownerId > 0L) { "Inline choice owner id must be positive" }
            require(historyRevision >= 0L) { "Inline choice history revision must not be negative" }
            require(originMessageId == null || originMessageId > 0L) {
                "Inline choice origin message id must be positive"
            }
        }
    }

    // an opt-in Bot API 10.1 rich message for large, genuinely structured replies (long comparisons,
    // tables, multi-section documents). [markdown] is github-flavored markdown; delivery falls back to a
    // .md document if Telegram rejects it. some third-party clients (e.g. Telegram X) render rich messages
    // as unsupported, so the normal HTML [Text] path stays the default for everyday replies.
    data class RichMessage(val markdown: String) : BotOutput() {
        init {
            require(markdown.isNotBlank()) { "RichMessage markdown must not be blank" }
        }
    }

    class Photo(val bytes: ByteArray, val filename: String, val fallbackToDocument: Boolean = true) : BotOutput() {
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
        val filename: String = "animation.gif"
    ) : BotOutput() {
        override val acceptsCaption: Boolean get() = true

        init {
            require((url != null) != (bytes != null)) {
                "Animation needs exactly one of url or bytes"
            }
        }
    }

    // sent by file_id from the learned sticker catalog. the Bot API takes no caption on a sticker, so
    // it is always a message of its own. [catalogId] travels with it so a rejected file_id can be
    // reported back to the catalog.
    data class Sticker(val fileId: String, val catalogId: Long) : BotOutput() {
        init {
            require(fileId.isNotBlank()) { "Sticker file id must not be blank" }
            require(catalogId > 0L) { "Sticker catalog id must be positive" }
        }
    }

    class Voice(
        val bytes: ByteArray,
        val durationSeconds: Int? = null
    ) : BotOutput()

    class VideoNote(
        val bytes: ByteArray,
        val durationSeconds: Int? = null,
        val size: Int? = null
    ) : BotOutput()

    class Video(
        val bytes: ByteArray,
        val filename: String,
        val durationSeconds: Int? = null,
        val width: Int? = null,
        val height: Int? = null,
        val thumbnail: ByteArray? = null,
        val sourceUrl: String? = null
    ) : BotOutput() {
        override val acceptsCaption: Boolean get() = true
    }

    class Audio(
        val bytes: ByteArray,
        val filename: String,
        val title: String,
        val performer: String,
        val durationSeconds: Int? = null,
        val trackUrl: String? = null
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
        val isAnonymous: Boolean = false
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
        val allowsMultipleAnswers: Boolean = false
    ) : BotOutput() {
        init {
            validateQuestionAndOptions("Poll", question, options)
        }
    }

    data class Reaction(val messageId: Long, val emoji: String) : BotOutput() {
        init {
            require(emoji.isNotBlank()) { "Reaction emoji must not be blank" }
        }
    }
}
