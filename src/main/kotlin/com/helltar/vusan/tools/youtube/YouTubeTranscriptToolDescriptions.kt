package com.helltar.vusan.tools.youtube

internal object YouTubeTranscriptToolDescriptions {

    const val READ_TRANSCRIPT =
        "Read what is actually said in a YouTube video by fetching its subtitle track, without downloading the video. " +
                """Use whenever the user asks about the content of a video on YouTube: to summarize it, to pull out the key points or timestamps-free facts, or to answer a question about it — for example "what is this video about", "summarize this talk", "did he say anything about prices".""" +
                "This reaches YouTube only, by link or by name; when the video is a file attached to the chat message, call `describeVideo` instead. " +
                "Prefer this over downloading the video when the user wants information rather than the file itself. " +
                "The transcript comes back in the video's own language, whichever that is, so expect to translate while answering. " +
                "Very long videos come back truncated, which the result states explicitly. " +
                "Answer from the returned transcript in the user's language, and do not paste the raw transcript back into the chat."

    const val READ_TRANSCRIPT_QUERY =
        "A direct YouTube URL, or a search query (title, topic, or both) when the user has not given a link. " +
                "Examples: `https://www.youtube.com/watch?v=dQw4w9WgXcQ`, `Karpathy state of GPT talk`."
}
