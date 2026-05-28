package com.helltar.vusan.i18n

internal object Messages {

    val startReply = """Hey! Just tell me what you need 👋"""

    val resetReply = """Chat history wiped — fresh start ✨"""

    val resetBusyReply = """Hold on, I'm still working on your previous request — hit /reset again once I'm done 😊"""

    val busyReply = """Hold on, I'm still working on your previous request 😊"""

    val fallbackErrorReply = """Something went sideways on my end — give it a minute and try again 🙏"""

    val privateBlockedNotice = """I tried to DM you, but I can't — please open my chat and press /start first, then ask again 🙏"""

    val voiceEmptyReply = """I couldn't hear anything in that voice message — try again or send it as text 👂"""

    val voiceTranscriptionFailedReply = """I couldn't transcribe that voice message — try again in a moment or send it as text 😬"""

    fun voiceTooLongReply(durationSeconds: Long, maxSeconds: Long): String =
        "That voice message is ${durationSeconds}s long — I can only transcribe up to ${maxSeconds}s. Send a shorter one or type it out ⏱️"

    fun taskMissedNotice(id: Long, title: String?, scheduledFor: String): String {
        val label = title?.let { """ "$it"""" } ?: ""
        return "⏰ Skipped task #$id$label scheduled for $scheduledFor — I was offline."
    }
}
