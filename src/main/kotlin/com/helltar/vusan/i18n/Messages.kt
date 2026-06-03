package com.helltar.vusan.i18n

interface Messages {

    val startReply: String
    val busyReply: String
    val fallbackErrorReply: String
    val privateBlockedNotice: String
    val voiceEmptyReply: String
    val voiceTranscriptionFailedReply: String

    fun voiceTooLongReply(durationSeconds: Long, maxSeconds: Long): String

    fun taskMissedNotice(id: Long, title: String?, scheduledFor: String): String

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

    override val startReply = """Hey! Just tell me what you need 👋"""

    override val busyReply = """Hold on, I'm still working on your previous request 😊"""

    override val fallbackErrorReply = """Something went sideways on my end — give it a minute and try again 🙏"""

    override val privateBlockedNotice = """I tried to DM you, but I can't — please open my chat and press /start first, then ask again 🙏"""

    override val voiceEmptyReply = """I couldn't hear anything in that voice message — try again or send it as text 👂"""

    override val voiceTranscriptionFailedReply = """I couldn't transcribe that voice message — try again in a moment or send it as text 😬"""

    override fun voiceTooLongReply(durationSeconds: Long, maxSeconds: Long): String =
        "That voice message is ${durationSeconds}s long — I can only transcribe up to ${maxSeconds}s. Send a shorter one or type it out ⏱️"

    override fun taskMissedNotice(id: Long, title: String?, scheduledFor: String): String {
        val label = title?.let { """ "$it"""" } ?: ""
        return "⏰ Skipped task #$id$label scheduled for $scheduledFor — I was offline."
    }
}

internal object UkrainianMessages : Messages {

    override val startReply = """Привіт! Просто скажи, що тобі треба 👋"""

    override val busyReply = """Зачекай, я ще працюю над твоїм попереднім запитом 😊"""

    override val fallbackErrorReply = """Щось пішло не так з мого боку — дай хвилинку й спробуй ще раз 🙏"""

    override val privateBlockedNotice = """Хотів написати тобі в особисті, але не виходить — відкрий мій чат, натисни /start, а потім спитай ще раз 🙏"""

    override val voiceEmptyReply = """Я нічого не розчув у цьому голосовому — спробуй ще раз або напиши текстом 👂"""

    override val voiceTranscriptionFailedReply = """Не вдалося розпізнати це голосове — спробуй за мить ще раз або напиши текстом 😬"""

    override fun voiceTooLongReply(durationSeconds: Long, maxSeconds: Long): String =
        "Це голосове триває ${durationSeconds}с — я можу розпізнати щонайбільше ${maxSeconds}с. Надішли коротше або напиши текстом ⏱️"

    override fun taskMissedNotice(id: Long, title: String?, scheduledFor: String): String {
        val label = title?.let { """ "$it"""" } ?: ""
        return "⏰ Пропустив завдання #$id$label, заплановане на $scheduledFor — я був офлайн."
    }
}
