package com.helltar.vusan.tools.tgchannel

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.tasks.Recurrence
import com.helltar.vusan.tools.suspendToolGuard
import kotlin.time.Duration.Companion.days

private val MAX_WINDOW = 30.days

@Suppress("unused")
class TelegramChannelTools(private val reader: TelegramChannelReader) : ToolSet {

    @Tool
    @LLMDescription(TelegramChannelToolDescriptions.READ_TELEGRAM_CHANNEL_POSTS)
    suspend fun readTelegramChannelPosts(
        @LLMDescription(TelegramChannelToolDescriptions.CHANNEL)
        channel: String,
        @LLMDescription(TelegramChannelToolDescriptions.WINDOW)
        window: String = "",
        @LLMDescription(TelegramChannelToolDescriptions.QUERY)
        query: String = "",
        @LLMDescription(TelegramChannelToolDescriptions.MAX_POSTS)
        maxPosts: Int = 0,
        @LLMDescription(TelegramChannelToolDescriptions.DESCRIBE_IMAGES)
        describeImages: Boolean = true,
        @LLMDescription(TelegramChannelToolDescriptions.IMAGE_FOCUS)
        imageFocus: String = ""
    ): String = suspendToolGuard {
        val trimmedWindow = window.trim()

        val parsedWindow =
            trimmedWindow
                .takeIf { it.isNotEmpty() }
                ?.let {
                    Recurrence.parseInterval(it)
                        ?: return@suspendToolGuard "Unknown window=`$it`. Use a duration like `6h`, `24h`, `2d`, or `7d`."
                }

        if (parsedWindow != null && parsedWindow > MAX_WINDOW)
            return@suspendToolGuard "Window `$trimmedWindow` is too long. A channel can be read back `30d` at most."

        reader.read(
            channel = channel,
            window = parsedWindow,
            query = query.trim(),
            maxPosts = maxPosts,
            describeImages = describeImages,
            imageFocus = imageFocus.trim()
        )
    }
}
