package com.helltar.vusan.agent

import com.helltar.vusan.common.asFileSize
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind

// the blocks the current request is wrapped in. adapters fill them, but the tags and wording live here,
// beside the contract that defines them, so no messenger drifts from what the system prompt promises.

private const val MAX_REPLIED_STORED_TEXT_CHARS = 600

/**
 * The message a request replies to, as far as the adapter could read it. Any of it may be someone else's text
 * (a display name, a file name, a caption) and is defused where the block is written, so the adapter only
 * caps it.
 */
internal data class RepliedMessageSummary(
    val type: String,
    val textOrCaption: String?,
    // the replied message's author, `you` for the bot. in a group the replier is often not who that message
    // was for, so this block is all the model gets about it.
    val author: String? = null,
    val metadata: List<String> = emptyList(),
    val transcript: String? = null,
)

/**
 * The request as the model sees it, with what it replies to and the part the sender quoted.
 * [currentMessageText] is already defused by the adapter, while the blocks it wrapped around it (a
 * transcript, an album) are meant to stay blocks, so nothing here defuses it again.
 */
internal fun formatAgentInput(
    currentMessageText: String,
    repliedMessage: RepliedMessageSummary?,
    quotedFragment: String?,
): String =
    buildReplyContextPrompt(currentMessageText, repliedMessage, quotedFragment) { it }

/** The same request as history keeps it, with the replied text capped. */
internal fun formatConversationInput(
    currentMessageText: String,
    repliedMessage: RepliedMessageSummary?,
    quotedFragment: String?,
): String =
    buildReplyContextPrompt(currentMessageText, repliedMessage, quotedFragment) {
        it.collapseWhitespaceAndCap(MAX_REPLIED_STORED_TEXT_CHARS).orEmpty()
    }

private fun buildReplyContextPrompt(
    currentMessageText: String,
    repliedMessage: RepliedMessageSummary?,
    quotedFragment: String?,
    transformText: (String) -> String,
): String {
    if (repliedMessage == null && quotedFragment == null) return currentMessageText

    // a quote of the whole message adds nothing and would read as a second piece of context.
    val fragment = quotedFragment?.takeUnless { it.trim() == repliedMessage?.textOrCaption?.trim() }

    return buildString {
        if (repliedMessage != null) {
            appendLine("<reply_context>")
            repliedMessage.author?.let { appendLine("- author: ${it.neutralizePromptBlocks()}") }
            appendLine("- type: ${repliedMessage.type}")

            if (repliedMessage.metadata.isNotEmpty()) {
                appendLine("- metadata:")
                repliedMessage.metadata.forEach { appendLine("  - ${it.neutralizePromptBlocks()}") }
            }

            // the replied message is someone else's and never went through inbound sanitizing.
            repliedMessage.textOrCaption?.let {
                appendLine(xmlBlock("text_caption", transformText(it).neutralizePromptBlocks()))
            }

            repliedMessage.transcript?.let {
                appendLine(xmlBlock("audio_transcript", transformText(it).neutralizePromptBlocks()))
            }

            appendLine("</reply_context>")
            appendLine()
        }

        // last before the request: the fragment is what the request is about.
        fragment?.let {
            appendLine(xmlBlock("quoted_fragment", it.neutralizePromptBlocks()))
            appendLine()
        }

        append(xmlBlock("user_message", currentMessageText))
    }
}

// without a sandbox there is no `runCommand`, and pointing the model there would have it promise a look it
// cannot take
private const val SANDBOX_COPY_NOTE =
    "The sandbox command or file-writing tool copies this file into the turn's own directory under `turns/` and returns its exact path. "

internal fun attachedFileContextBlock(file: AttachedFile, sandbox: Boolean): String =
    xmlBlock(
        "attached_file",
        buildString {
            appendLine("name: ${file.name}")
            file.fileSizeBytes?.let { appendLine("size: ${it.asFileSize()}") }
            file.durationSeconds?.let { appendLine("duration: ${it}s") }

            when (file.kind) {
                AttachedFileKind.IMAGE -> {
                    if (sandbox) append(SANDBOX_COPY_NOTE)
                    append("It is an image: call `describeImage` to answer about what is visible")
                    if (sandbox) append(", or work on it with `runCommand` (resize, filter, colors, dimensions)")
                    append('.')
                }

                // the GIF line lives here, not in the no-caption prompt: a caption replaces that prompt, and
                // a reaction still is not something to review.
                AttachedFileKind.VIDEO ->
                    if (file.isAnimation)
                        append("It is a GIF: a short soundless loop, usually thrown into a chat as a reaction rather than as something to review. Call `describeVideo` only when the user asks what is in it, and never narrate it unasked.")
                    else {
                        append("It is a video: call `describeVideo` when your answer depends on what happens in it or what is said in it.")
                        if (sandbox) append(" To convert, cut or re-encode it, use the sandbox; its command or file-writing tool copies the file into the turn's own directory under `turns/` and returns its exact path.")
                    }

                AttachedFileKind.OTHER ->
                    if (sandbox) {
                        append(SANDBOX_COPY_NOTE)
                        append("Read it there with `runCommand` instead of asking the user to resend it.")
                    } else {
                        append("Its contents cannot be opened here: answer from its name and what the user says about it, and say so when the request depends on what is inside.")
                    }
            }
        },
    )

/** Several items sent as one message, and which tools see which. */
internal fun albumContextBlock(
    itemCount: Int,
    photoCount: Int,
    videoCount: Int,
    attachedFiles: List<AttachedFile>,
    sandbox: Boolean,
): String {
    val otherCount = itemCount - photoCount - videoCount
    val attachedImages = attachedFiles.count { it.kind == AttachedFileKind.IMAGE }
    val attachedVisuals = attachedFiles.count { it.kind != AttachedFileKind.OTHER }

    return xmlBlock(
        "album",
        buildString {
            append("User sent an album of $itemCount item(s): ")
            append("$photoCount photo(s), $videoCount video(s), $otherCount other file(s). ")

            if (attachedFiles.isEmpty()) {
                append("None of the items is available as an attached file; mention this if the request depends on them.")
                return@buildString
            }

            // the sender picked every one of these names.
            val names = attachedFiles.joinToString(", ") { "`${it.name.neutralizePromptBlocks()}`" }

            if (attachedFiles.size < itemCount)
                append("Only ${attachedFiles.size} of them are attached, and the rest cannot be opened: $names. ")
            else
                append("All of them are attached: $names. ")

            if (sandbox) append("The sandbox command or file-writing tool copies every attached item into the turn's own directory under `turns/` and returns each exact path. ")

            if (attachedImages > 1)
                append(
                    "`editImage` works on all $attachedImages images together — combining them, " +
                            "putting one into another, building a collage. ",
                )

            if (attachedVisuals > 1)
                append("`describeImage` and `describeVideo` see only the first item; mention this if the request depends on how the others look.")
        }.trim(),
    )
}

/**
 * The turn a scheduled task runs, with no message behind it: the task's title, recurrence and [body], the
 * words it was set up with, defused like any user text.
 */
internal fun scheduledTaskBlock(title: String?, recurrence: String, body: String): String =
    xmlBlock(
        "scheduled_task",
        buildString {
            title?.let { appendLine("title: ${it.neutralizePromptBlocks()}") }
            appendLine("recurrence: $recurrence")
            appendLine()
            append(body.neutralizePromptBlocks())
        },
    )

// a rich message flattened to markdown never went through the sanitizing ordinary text gets.
internal fun wrapRichMessage(markdown: String): String =
    xmlBlock("rich_message", markdown.neutralizePromptBlocks())

internal fun wrapAudioTranscript(text: String): String =
    xmlBlock("audio_transcript", text.neutralizePromptBlocks())

/** A pressed button the model offered: its question and the chosen option. */
internal fun inlineChoiceInput(question: String, option: String): String =
    xmlBlock(
        "inline_choice",
        buildString {
            appendLine(xmlBlock("question", question))
            append(xmlBlock("selected_option", option))
        },
    )

// for a message with no words: media alone, a bare mention. it names tools, so it is prompt text and lives
// here, not in an adapter
    internal const val MENTION_ONLY_PROMPT = "User mentioned the bot with no text. Respond naturally and briefly."

    // an uncaptioned media message is a conversation move, not a request for a report; the describe tools are
    // for when the answer depends on the content.
    internal const val MEDIA_ONLY_PROMPT =
        "User sent this with no caption, so the media itself is their whole message. " +
                "Reply the way a person would at this point in the conversation. " +
                "Look at it (`describeImage`, `describeVideo`) or process it (`runCommand`) only when your answer depends on what is inside, " +
                "and do not narrate what you saw unless the user asked what is in it."

    // a gif is a reaction, like a sticker, not something to review.
    internal const val ANIMATION_ONLY_PROMPT =
        "User answered with a GIF and no caption, the way one reacts with a sticker instead of typing. " +
                "Treat it as their reaction, match its mood, and keep the conversation going in your own voice. " +
                "Call `describeVideo` only if they ask what is in it; never describe or narrate it unasked."

    // a round video message is the user talking: the speech is the message, not the picture.
    internal const val VIDEO_NOTE_ONLY_PROMPT =
        "User sent a video note (a round video message) with no caption — it is them speaking to you. " +
                "Call `describeVideo` to get what they said, then answer that. " +
                "Do not describe how the video looks unless they ask."
