package com.helltar.vusan.agent

import com.helltar.vusan.tools.files.FileTools
import com.helltar.vusan.tools.giphy.GiphyTools
import com.helltar.vusan.tools.imagegen.ImageGenTools
import com.helltar.vusan.tools.tavily.TavilyTools
import com.helltar.vusan.tools.voice.VoiceTools
import com.helltar.vusan.tools.youtube.YouTubeMusicTools
import com.helltar.vusan.tools.youtube.YouTubeVideoTools

/**
 * What a tool is about to produce, surfaced while it runs so the chat can show a matching action
 * (e.g. "sending photo" during image generation). Deliberately neutral: the Telegram layer maps it
 * to a concrete chat action, so no Telegram type leaks into [com.helltar.vusan.tools].
 */
enum class ToolActivity { PHOTO, VIDEO, VOICE, DOCUMENT, TEXT }

// tool names arrive from Koog's onToolCallStarting (the @Tool method name). method references keep
// this map in sync with renames; any tool not listed defaults to TEXT, i.e. plain typing.
private val TOOL_ACTIVITIES: Map<String, ToolActivity> = buildMap {
    put(ImageGenTools::generateImage.name, ToolActivity.PHOTO)
    put(ImageGenTools::editImage.name, ToolActivity.PHOTO)
    put(TavilyTools::searchImages.name, ToolActivity.PHOTO)
    put(GiphyTools::searchGif.name, ToolActivity.VIDEO)
    put(YouTubeVideoTools::downloadVideo.name, ToolActivity.VIDEO)
    put(VoiceTools::speakWithVoice.name, ToolActivity.VOICE)
    put(FileTools::sendFile.name, ToolActivity.DOCUMENT)
    put(YouTubeMusicTools::playFullTrack.name, ToolActivity.DOCUMENT)
}

internal fun toolActivityFor(toolName: String): ToolActivity =
    TOOL_ACTIVITIES[toolName] ?: ToolActivity.TEXT
