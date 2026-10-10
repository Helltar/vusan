package com.helltar.vusan.tools.voice

import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.config.ElevenLabsTtsConfig
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.tools.keepOnShelf
import com.helltar.vusan.tools.keptNotSent
import com.helltar.vusan.tools.refusedByChat
import com.helltar.vusan.tools.suspendToolGuard
import io.github.oshai.kotlinlogging.KotlinLogging

class VoiceTools(
    private val client: ElevenLabsTtsClient,
    private val config: ElevenLabsTtsConfig,
    private val outbox: BotOutbox,
) : ToolSet {

    @Tool(VoiceToolDescriptions.SPEAK_WITH_VOICE)
    suspend fun speakWithVoice(
        @Arg(VoiceToolDescriptions.TEXT, takesReference = true)
        text: String,
        @Arg(VoiceToolDescriptions.SEND)
        send: Boolean = true,
    ): String = suspendToolGuard {
        val trimmed = text.trim()

        if (trimmed.isEmpty())
            return@suspendToolGuard "Voice text is empty — nothing to speak."

        if (trimmed.length > VOICE_TOOLS_MAX_CHARS)
            return@suspendToolGuard "Voice text is ${trimmed.length} characters, " +
                    "which exceeds the $VOICE_TOOLS_MAX_CHARS-character limit. Shorten it and try again."

        if (send && !outbox.capabilities.voiceNotes)
            return@suspendToolGuard refusedByChat("voice messages")

        val bytes =
            runCatching { client.synthesize(trimmed, config) }
                .getOrElse { e ->
                    e.rethrowIfCancellation()

                    log.warn(e) {
                        "ElevenLabs TTS synthesize failed: model=${config.model} voiceId=${config.voiceId} " +
                                "textChars=${trimmed.length}"
                    }

                    error("Voice synthesis failed: ${e.message ?: e::class.simpleName}")
                }

        val label = keepOnShelf(SPEECH_FILE_NAME, bytes)

        if (!send) return@suspendToolGuard keptNotSent("The speech (${trimmed.length} chars, mp3)", label)

        outbox.enqueue(BotOutput.Voice(bytes))

        "Voice message queued (${trimmed.length} chars, ${bytes.size} bytes). " +
                "Do not add a separate user-facing confirmation."
    }

    companion object {
        // about two minutes of speech at the thousand characters a minute the provider itself reckons with,
        // and well under what it takes in one request
        const val VOICE_TOOLS_MAX_CHARS = 2000

        // the API's default output, which Telegram takes as a voice message as it is
        const val SPEECH_FILE_NAME = "speech.mp3"

        private val log = KotlinLogging.logger {}
    }
}
