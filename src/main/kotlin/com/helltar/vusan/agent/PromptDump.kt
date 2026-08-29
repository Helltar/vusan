package com.helltar.vusan.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.MessagePart
import io.github.oshai.kotlinlogging.KotlinLogging

// every message of the request, uncut, in the order the provider receives it. raw user content and
// tool output ride along, so this stays off until PROMPT_DUMP_LEVEL=DEBUG asks for it.
private val log = KotlinLogging.logger("PromptDump")

internal fun logPromptDump(prompt: Prompt, model: String, tools: List<ToolDescriptor>) {
    log.debug { renderPromptDump(prompt, model, tools.map { it.name }) }
}

internal fun renderPromptDump(prompt: Prompt, model: String, tools: List<String>): String =
    buildString {
        append("llm request: model=[$model] messages=${prompt.messages.size} tools=[${tools.joinToString(", ")}]")

        prompt.messages.forEach { message ->
            append("\n\n--- ${message.role.name.lowercase()} ---\n")
            append(message.parts.joinToString("\n", transform = ::renderPart))
        }
    }

private fun renderPart(part: MessagePart): String =
    when (part) {
        is MessagePart.Text -> part.text
        is MessagePart.Reasoning -> "[reasoning] ${part.content.joinToString("\n")}"
        is MessagePart.Attachment -> renderAttachment(part)
        is MessagePart.Tool.Call -> "[tool call ${part.tool} id=${part.id}] ${part.args}"

        is MessagePart.Tool.Result ->
            "[tool result ${part.tool} id=${part.id} error=${part.isError}]\n" +
                    part.parts.joinToString("\n", transform = ::renderPart)
    }

// binary attachments are sent base64-encoded; the bytes are megabytes of noise, so only their size
// is logged, while text and URL sources are shown as the model gets them.
private fun renderAttachment(part: MessagePart.Attachment): String {
    val source = part.source
    val content =
        when (val attached = source.content) {
            is AttachmentContent.PlainText -> attached.text
            is AttachmentContent.URL -> attached.url
            is AttachmentContent.Binary.Bytes -> "${attached.data.size} bytes"
            is AttachmentContent.Binary.Base64 -> "${attached.base64.length} base64 chars"
        }

    return "[attachment ${source.mimeType} name=${source.fileName.orEmpty()}] $content"
}
