package com.helltar.vusan.tools.voice

import com.helltar.vusan.tools.voice.VoiceTools.Companion.VOICE_TOOLS_MAX_CHARS

internal object VideoNoteToolDescriptions {

    const val SPEAK_AS_VIDEO_NOTE =
        "Speaks the given text aloud and sends it as a Telegram round video message — your own face in the circle, with the waveform of your voice moving as you talk. " +
                """Use when the user asks for a round video message, a video note, or a "circle", and for a greeting, a toast, or a punchline that is better watched than read. """ +
                "Prefer `speakWithVoice` when the user only asked to hear something out loud: a voice message plays in the background, while a round video takes over the screen. " +
                VoiceToolDescriptions.AUDIO_TAG_RULES +
                "Hard limit: `text` must be at most $VOICE_TOOLS_MAX_CHARS characters (tags count toward this). " +
                "If longer, shorten it yourself before calling. " +
                "Write the spoken words in the same language the user spoke. " +
                "The resulting voice is AI-generated. " +
                "After a successful call, do not send a separate confirmation; the video message is delivered automatically. " +
                "Use `sendMessage` only if the user explicitly asked for additional visible text or if the tool fails."

    const val TEXT =
        "The exact words to speak, in natural prose, in the user's language. " +
                "Up to $VOICE_TOOLS_MAX_CHARS characters total, including any audio tags. " +
                "No SSML, no markdown."
}
