package com.helltar.vusan.agent

import com.helltar.vusan.llm.Message
import com.helltar.vusan.llm.Part
import io.github.oshai.kotlinlogging.KotlinLogging

// every request message, uncut, in the order the provider receives it. raw user content and tool output ride
// along, so this stays off unless PROMPT_DUMP_LEVEL=DEBUG.
private val log = KotlinLogging.logger("PromptDump")

internal fun logPromptDump(messages: List<Message>, model: String, tools: List<String>) {
    log.debug { renderPromptDump(messages, model, tools) }
}

internal fun renderPromptDump(messages: List<Message>, model: String, tools: List<String>): String =
    buildString {
        append("llm request: model=[$model] messages=${messages.size} tools=[${tools.joinToString(", ")}]")

        messages.forEach { message ->
            when (message) {
                is Message.System -> append("\n\n--- system ---\n").append(message.text)
                is Message.User -> append("\n\n--- user ---\n").append(message.parts.joinToString("\n", transform = ::renderPart))
                is Message.Assistant -> append("\n\n--- assistant ---\n").append(message.parts.joinToString("\n", transform = ::renderPart))

                is Message.ToolResults ->
                    append("\n\n--- tool results ---\n").append(
                        message.results.joinToString("\n") { "[tool result ${it.name} id=${it.callId} error=${it.isError}]\n${it.output}" },
                    )

                is Message.ToolsOffered -> append("\n\n--- tools offered ---\n").append(message.names.joinToString(", "))
            }
        }
    }

// an image's base64 is megabytes of noise and a reasoning block is the provider's to read, so only size and
// presence are logged.
private fun renderPart(part: Part): String =
    when (part) {
        is Part.Text -> part.text
        is Part.Reasoning -> "[reasoning from ${part.source}]"
        is Part.Image -> "[image ${part.mimeType} name=${part.fileName.orEmpty()}] ${part.bytes.size} bytes"
        is Part.ToolCall -> "[tool call ${part.name} id=${part.id}] ${part.arguments}"
    }
