package com.helltar.vusan.outbox

sealed class BotOutput {

    open val acceptsCaption: Boolean
        get() = false

    data class Text(val text: String) : BotOutput()

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
