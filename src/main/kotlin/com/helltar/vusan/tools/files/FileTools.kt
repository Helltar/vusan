package com.helltar.vusan.tools.files

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.common.sanitizeFilename
import com.helltar.vusan.tools.common.suspendToolGuard

@Suppress("unused")
class FileTools(private val outbox: BotOutbox) : ToolSet {

    @Tool
    @LLMDescription(FileToolDescriptions.SEND_FILE)
    suspend fun sendFile(
        @LLMDescription(FileToolDescriptions.CONTENT)
        content: String,
        @LLMDescription(FileToolDescriptions.FILENAME)
        filename: String
    ): String = suspendToolGuard {
        require(content.isNotEmpty()) { "File content must not be empty" }

        val safeName = filename.sanitizeFilename().ifBlank { "file.txt" }
        outbox.enqueue(BotOutput.Document(bytes = content.toByteArray(Charsets.UTF_8), filename = safeName))

        """File "$safeName" ready (${content.length} chars) and will be sent."""
    }
}
