package com.helltar.vusan.tools.speech

internal object SpeechToolDescriptions {

    const val TRANSCRIBE_AUDIO =
        "Writes down what is said in a recording through speech-to-text: an audio or video file attached to the request, or the one `file` names. " +
                "It takes the first five minutes at most; a longer recording, or one part of it worth a closer listen, is cut in the sandbox first and transcribed piece by piece. " +
                "A voice message the user sent is already transcribed in the request, so it needs no call. " +
                "Does nothing when no recording is attached or named."

    const val FILE =
        "Optional recording to transcribe instead of the attachment: a file an earlier call made (`#2/1`) or one in the sandbox (`sandbox:speech.m4a`)."

    const val LANGUAGE =
        "Optional ISO-639-1 code of the language spoken (`ky`, `uk`, `en`), when you know it; leave it out to let the language be detected."
}
