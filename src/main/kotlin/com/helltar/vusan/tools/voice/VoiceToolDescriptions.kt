package com.helltar.vusan.tools.voice

import com.helltar.vusan.tools.voice.VoiceTools.Companion.VOICE_TOOLS_MAX_CHARS

internal object VoiceToolDescriptions {

    const val SPEAK_WITH_VOICE =
        "Speaks the given text aloud and sends it as a Telegram voice message via the ElevenLabs `eleven_v3` TTS model. " +
                "Use when the user asks to speak, say, voice, or pronounce something out loud (`say it out loud`, `send a voice message`, `read this aloud`). " +

                "By default, send plain prose with NO audio tags — the default voice already sounds natural and unwanted tags make it theatrical. " +
                "Add `eleven_v3` audio tags in square brackets only when the user explicitly directs the delivery: " +
                "asks for a manner (`say it quietly`, `like a pirate`, `sarcastically`), scripts the line themselves " +
                """(`start quietly with "hi", then loudly "how are you?"`), or asks for a clearly performative read (poem, dialogue, dramatic scene). """ +

                "When tags are warranted, the set is open-ended — invent whatever bracketed cue fits (emotion, volume, non-verbal reactions, " +
                "pacing, emphasis, accents, character voices, sound effects). Layer freely (`[British accent][exasperated]`), place each tag " +
                "right before the words it should color, and keep tag names in English even when the spoken text is in another language. " +
                "For accents, prefix with `strong` for thicker (`[strong French accent]`). " +
                "Example: `[whispers] hi [shouts] how are you?`. " +

                "Hard limit: `text` must be at most $VOICE_TOOLS_MAX_CHARS characters (tags count toward this). If longer, shorten it yourself before calling. " +
                "Write the spoken words in the same language the user spoke. The resulting voice is AI-generated. " +
                "After a successful call, do not send a separate confirmation; the voice message is delivered automatically. " +
                "Use `sendMessage` only if the user explicitly asked for additional visible text or if the tool fails."

    const val TEXT =
        "The exact words to speak, in natural prose, in the user's language. " +
                "Up to $VOICE_TOOLS_MAX_CHARS characters total, including any audio tags. No SSML, no markdown."
}
