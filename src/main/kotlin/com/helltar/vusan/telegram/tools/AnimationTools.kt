package com.helltar.vusan.telegram.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.telegram.downloadFileById
import com.helltar.vusan.tools.files.asFileSize
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard
import org.telegram.telegrambots.meta.generics.TelegramClient

@Suppress("unused")
class AnimationTools(
    private val telegram: TelegramClient,
    private val outbox: BotOutbox,
) : ToolSet {

    @Tool
    @LLMDescription(AnimationToolDescriptions.SEND_ANIMATION)
    suspend fun sendAnimation(
        @LLMDescription(ChatFileToolDescriptions.CHAT_FILE_ID)
        fileId: String,
    ): String = suspendToolGuard {
        val id = fileId.requireToolText("File id", MAX_FILE_ID_CHARS)
        val file = telegram.downloadFileById(id)
        val filename = file.path?.substringAfterLast('/').orEmpty()
        require(filename.substringAfterLast('.', "").lowercase() in setOf("gif", "mp4")) {
            "Animation requires a GIF or a silent H.264 MP4; Telegram served a different file type."
        }

        // A video file_id cannot change media type. Reupload the same bytes as an animation.
        outbox.enqueue(BotOutput.Animation(bytes = file.bytes, filename = filename))

        "Queued animation \"$filename\" (${file.bytes.size.toLong().asFileSize()}) for delivery. " +
                "The bytes are unchanged; no audio removal or transcoding was performed."
    }

    private companion object {
        const val MAX_FILE_ID_CHARS = 200
    }
}
