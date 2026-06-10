package com.helltar.vusan.tools.voice

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.config.ElevenLabsTtsConfig
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.tools.suspendToolGuard
import io.github.oshai.kotlinlogging.KotlinLogging

@Suppress("unused")
class VoiceTools(
    private val client: ElevenLabsTtsClient,
    private val config: ElevenLabsTtsConfig,
    private val outbox: BotOutbox
) : ToolSet {

    companion object {
        const val VOICE_TOOLS_MAX_CHARS = 500
        private val log = KotlinLogging.logger {}
    }

    @Tool
    @LLMDescription(VoiceToolDescriptions.SPEAK_WITH_VOICE)
    suspend fun speakWithVoice(
        @LLMDescription(VoiceToolDescriptions.TEXT)
        text: String
    ): String = suspendToolGuard {
        val trimmed = text.trim()

        if (trimmed.isEmpty())
            return@suspendToolGuard "Voice text is empty — nothing to speak."

        if (trimmed.length > VOICE_TOOLS_MAX_CHARS)
            return@suspendToolGuard "Voice text is ${trimmed.length} characters, " +
                    "which exceeds the $VOICE_TOOLS_MAX_CHARS-character limit. Shorten it and try again."

        val bytes =
            runCatching { client.synthesize(trimmed, config) }
                .getOrElse { e ->
                    e.rethrowIfCancellation()

                    log.warn(e) {
                        "ElevenLabs TTS synthesize failed: model=${config.model} voiceId=${config.voiceId} " +
                                "textChars=${trimmed.length}"
                    }

                    return@suspendToolGuard "Voice synthesis failed: ${e.message ?: e::class.simpleName}"
                }

        outbox.enqueue(BotOutput.Voice(bytes))

        "Voice message queued (${trimmed.length} chars, ${bytes.size} bytes). " +
                "Do not add a separate user-facing confirmation."
    }
}
