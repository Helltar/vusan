package com.helltar.vusan.agent

import com.helltar.vusan.tools.files.FileTools
import com.helltar.vusan.tools.giphy.GiphyTools
import com.helltar.vusan.tools.grouplog.GroupLogTools
import com.helltar.vusan.tools.imagegen.ImageGenTools
import com.helltar.vusan.tools.memory.MemoryTools
import com.helltar.vusan.tools.message.MessageTools
import com.helltar.vusan.tools.searxng.SearxngTools
import com.helltar.vusan.tools.tasks.TaskTools
import com.helltar.vusan.tools.tavily.TavilyTools
import com.helltar.vusan.tools.tgchannel.TelegramChannelTools
import com.helltar.vusan.tools.vision.VisionTools
import com.helltar.vusan.tools.voice.VoiceTools
import com.helltar.vusan.tools.youtube.YouTubeMusicTools
import com.helltar.vusan.tools.youtube.YouTubeTranscriptTools
import com.helltar.vusan.tools.youtube.YouTubeVideoTools
import com.helltar.vusan.tools.workspace.WorkspaceTools

/**
 * What the agent is busy with, surfaced while a tool runs so the chat can show it — as a chat action,
 * and in a private chat as the words of the progress draft. Deliberately neutral: the Telegram layer
 * owns both renderings, so no Telegram type leaks into [com.helltar.vusan.tools].
 *
 * `null` means nothing worth naming is running: the model is thinking, or the tool is over before a
 * caption for it could be read.
 */
enum class ToolActivity {
    WRITING,
    SEARCHING_WEB,
    READING_PAGE,
    READING_CHANNEL,
    READING_TRANSCRIPT,
    READING_CHAT_LOG,
    SEARCHING_IMAGES,
    SEARCHING_GIF,
    DRAWING,
    RUNNING_CODE,
    LOOKING_AT_IMAGE,
    WATCHING_VIDEO,
    DOWNLOADING_VIDEO,
    DOWNLOADING_AUDIO,
    SENDING_FILE,
    SPEAKING,
    REMEMBERING,
    MANAGING_TASKS
}

// tool names arrive from Koog's onToolCallStarting (the @Tool method name). method references keep
// this map in sync with renames. tools left out are the instant ones — a poll, a reaction, a sticker,
// an exchange rate — where a caption would flash by before it could be read.
private val TOOL_ACTIVITIES: Map<String, ToolActivity> = buildMap {
    put(MessageTools::sendMessage.name, ToolActivity.WRITING)
    put(MessageTools::sendRichMessage.name, ToolActivity.WRITING)
    put(TavilyTools::webSearch.name, ToolActivity.SEARCHING_WEB)
    put(SearxngTools::metaSearch.name, ToolActivity.SEARCHING_WEB)
    put(TavilyTools::extractPageContent.name, ToolActivity.READING_PAGE)
    put(TelegramChannelTools::readTelegramChannelPosts.name, ToolActivity.READING_CHANNEL)
    put(YouTubeTranscriptTools::readYouTubeTranscript.name, ToolActivity.READING_TRANSCRIPT)
    put(GroupLogTools::readGroupLog.name, ToolActivity.READING_CHAT_LOG)
    put(TavilyTools::searchImages.name, ToolActivity.SEARCHING_IMAGES)
    put(SearxngTools::metaSearchImages.name, ToolActivity.SEARCHING_IMAGES)
    put(GiphyTools::searchGif.name, ToolActivity.SEARCHING_GIF)
    put(ImageGenTools::generateImage.name, ToolActivity.DRAWING)
    put(ImageGenTools::editImage.name, ToolActivity.DRAWING)
    put(WorkspaceTools::runCommand.name, ToolActivity.RUNNING_CODE)
    put(WorkspaceTools::writeWorkspaceFile.name, ToolActivity.RUNNING_CODE)
    put(VisionTools::describeImage.name, ToolActivity.LOOKING_AT_IMAGE)
    put(VisionTools::describeVideo.name, ToolActivity.WATCHING_VIDEO)
    put(YouTubeVideoTools::downloadVideo.name, ToolActivity.DOWNLOADING_VIDEO)
    put(YouTubeMusicTools::playFullTrack.name, ToolActivity.DOWNLOADING_AUDIO)
    put(FileTools::sendFile.name, ToolActivity.SENDING_FILE)
    put(FileTools::downloadFile.name, ToolActivity.SENDING_FILE)
    put(WorkspaceTools::sendFromWorkspace.name, ToolActivity.SENDING_FILE)
    put(VoiceTools::speakWithVoice.name, ToolActivity.SPEAKING)
    put(MemoryTools::rememberAboutMe.name, ToolActivity.REMEMBERING)
    put(MemoryTools::rememberAboutGroup.name, ToolActivity.REMEMBERING)
    put(MemoryTools::forgetMemory.name, ToolActivity.REMEMBERING)
    put(MemoryTools::forgetEverythingAboutMe.name, ToolActivity.REMEMBERING)
    put(TaskTools::scheduleTask.name, ToolActivity.MANAGING_TASKS)
    put(TaskTools::scheduleFollowUp.name, ToolActivity.MANAGING_TASKS)
    put(TaskTools::editTask.name, ToolActivity.MANAGING_TASKS)
    put(TaskTools::listTasks.name, ToolActivity.MANAGING_TASKS)
    put(TaskTools::pauseTask.name, ToolActivity.MANAGING_TASKS)
    put(TaskTools::resumeTask.name, ToolActivity.MANAGING_TASKS)
    put(TaskTools::cancelTask.name, ToolActivity.MANAGING_TASKS)
}

internal fun toolActivityFor(toolName: String): ToolActivity? = TOOL_ACTIVITIES[toolName]
