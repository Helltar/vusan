package com.helltar.vusan.i18n

interface Messages {

    val startReply: String
    val busyReply: String
    val fallbackErrorReply: String
    val overloadedReply: String
    val formattingAsFileNotice: String
    val privateBlockedNotice: String
    val voiceEmptyReply: String
    val voiceTranscriptionFailedReply: String

    fun voiceTooLongReply(durationSeconds: Long, maxSeconds: Long): String

    fun taskMissedNotice(id: Long, title: String?, scheduledFor: String): String

    fun taskScheduledByNotice(mention: String): String

    companion object {
        fun of(language: Language): Messages =
            when (language) {
                Language.ENGLISH -> EnglishMessages
                Language.UKRAINIAN -> UkrainianMessages
            }

        fun forCode(code: String?): Messages = of(Language.fromCode(code))
    }
}

internal object EnglishMessages : Messages {

    override val startReply = "Hey! Just tell me what you need 👋"

    override val busyReply = "Hold on, I'm still working on your previous request 😊"

    override val fallbackErrorReply = "Something went wrong — try again? 🥲"

    override val overloadedReply = "I'm a bit overloaded right now — give me a moment and try again 🙏"

    override val formattingAsFileNotice =
        "Telegram couldn't display the formatting, so here's the full reply as a file 📄"

    override val privateBlockedNotice =
        "I tried to DM you, but I can't — please open my chat and press /start first, then ask again 😊"

    override val voiceEmptyReply = "I couldn't hear anything in that voice message — try again or send it as text 🙉"

    override val voiceTranscriptionFailedReply = "I couldn't transcribe that voice message — send it as text instead 😊"

    override fun voiceTooLongReply(durationSeconds: Long, maxSeconds: Long): String =
        "That voice message is ${durationSeconds}s long — I can only transcribe up to ${maxSeconds}s, " +
                "send a shorter one or type it out"

    override fun taskMissedNotice(id: Long, title: String?, scheduledFor: String): String {
        val label = title?.let { " «$it»" } ?: ""
        return "⏰ Skipped task #$id$label scheduled for $scheduledFor — I was offline."
    }

    override fun taskScheduledByNotice(mention: String) = "⏰ Scheduled by $mention"
}

internal object UkrainianMessages : Messages {

    override val startReply = "Привіт! Просто скажи, що тобі треба 👋"

    override val busyReply = "Зачекай, я ще працюю над твоїм попереднім запитом 😊"

    override val fallbackErrorReply = "Щось пішло не так — спробуй ще раз? 🥲"

    override val overloadedReply = "Зараз я трохи перевантажена — дай хвилинку й спробуй ще раз 🙏"

    override val formattingAsFileNotice =
        "Телеграм не зміг показати форматування, тож ось повна відповідь файлом 📄"

    override val privateBlockedNotice =
        "Хотіла написати тобі в особисті, але не виходить — відкрий мій чат, натисни /start, а потім спитай ще раз 😊"

    override val voiceEmptyReply = "Я нічого не розчула у цьому голосовому — спробуй ще раз або напиши текстом 🙉"

    override val voiceTranscriptionFailedReply = "Не вдалося розпізнати це голосове — напиши краще текстом 😊"

    override fun voiceTooLongReply(durationSeconds: Long, maxSeconds: Long): String =
        "Це голосове триває ${durationSeconds}с — я можу розпізнати щонайбільше ${maxSeconds}с, " +
                "надішли коротше або напиши текстом"

    override fun taskMissedNotice(id: Long, title: String?, scheduledFor: String): String {
        val label = title?.let { " «$it»" } ?: ""
        return "⏰ Пропустила завдання #$id$label, заплановане на $scheduledFor — я була офлайн."
    }

    override fun taskScheduledByNotice(mention: String) = "⏰ Заплановано: $mention"
}
