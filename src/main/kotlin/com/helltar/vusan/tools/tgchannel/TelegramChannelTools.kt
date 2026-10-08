package com.helltar.vusan.tools.tgchannel

import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.tasks.Recurrence
import com.helltar.vusan.tools.suspendToolGuard
import kotlin.time.Duration.Companion.days

private val MAX_WINDOW = 30.days

class TelegramChannelTools(private val reader: TelegramChannelReader) : ToolSet {

    @Tool(TelegramChannelToolDescriptions.READ_TELEGRAM_CHANNEL_POSTS, readOnly = true)
    suspend fun readTelegramChannelPosts(
        @Arg(TelegramChannelToolDescriptions.CHANNEL)
        channel: String,
        @Arg(TelegramChannelToolDescriptions.WINDOW)
        window: String = "",
        @Arg(TelegramChannelToolDescriptions.QUERY)
        query: String = "",
        @Arg(TelegramChannelToolDescriptions.MAX_POSTS)
        maxPosts: Int = 0,
        @Arg(TelegramChannelToolDescriptions.DESCRIBE_IMAGES)
        describeImages: Boolean = true,
        @Arg(TelegramChannelToolDescriptions.IMAGE_FOCUS)
        imageFocus: String = "",
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
            imageFocus = imageFocus.trim(),
        )
    }
}
