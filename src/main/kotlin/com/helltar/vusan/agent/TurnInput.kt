package com.helltar.vusan.agent

import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.xmlBlock
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import java.util.Locale

// the blocks the current request is wrapped in. an adapter reads what it received and fills them in,
// but the tags and their wording are written here once, beside the contract that says what each of
// them means: a messenger that spelled its own would drift from what the system prompt promises.

private const val MAX_REPLIED_STORED_TEXT_CHARS = 600

/**
 * The message a request replies to, as far as the adapter could read it.
 *
 * Anything in it may be somebody else's text — a display name, a file name, a caption — and all of it
 * is defused where the block is written, so the adapter only has to cap what it reads.
 */
internal data class RepliedMessageSummary(
    val type: String,
    val textOrCaption: String?,
    // who wrote the message being replied to, `you` when it is the bot's own. in a group the person
    // replying is often not the one that message was written for, so their history carries nothing
    // about it — this block is then all the model gets.
    val author: String? = null,
    val metadata: List<String> = emptyList(),
    val transcript: String? = null,
)

/**
 * The request as the model is shown it, with what it replies to and the part of it the sender quoted.
 *
 * [currentMessageText] is the request the adapter assembled: text anybody typed in it is already
 * defused with [neutralizePromptBlocks], while the blocks the adapter wrapped around it — a transcript,
 * an album — are meant to stay blocks, which is why nothing here defuses it a second time.
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

    // quoting the whole message says nothing beyond the reply itself, and repeating it would read as
    // two different pieces of context.
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

            // the replied message is somebody else's, and none of it passed through inbound sanitizing.
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

internal fun attachedFileContextBlock(file: AttachedFile): String =
    xmlBlock(
        "attached_file",
        buildString {
            appendLine("name: ${file.name}")
            file.fileSizeBytes?.let { appendLine("size: ${formatFileSize(it)}") }
            file.durationSeconds?.let { appendLine("duration: ${it}s") }

            when (file.kind) {
                AttachedFileKind.IMAGE -> {
                    append("The sandbox command or file-writing tool copies this file into `inbox/` and returns its exact path. ")
                    append("It is an image: call `describeImage` to answer about what is visible, or work on it with `runCommand` (resize, filter, colors, dimensions).")
                }

                // the GIF line has to live here rather than in the no-caption prompt: a caption replaces
                // that prompt, and the reaction still is not something to review.
                AttachedFileKind.VIDEO ->
                    if (file.isAnimation)
                        append("It is a GIF: a short soundless loop, usually thrown into a chat as a reaction rather than as something to review. Call `describeVideo` only when the user asks what is in it, and never narrate it unasked.")
                    else {
                        append("It is a video: call `describeVideo` when your answer depends on what happens in it or what is said in it. ")
                        append("To convert, cut or re-encode it, use the sandbox; its command or file-writing tool copies the file into `inbox/` and returns its exact path.")
                    }

                AttachedFileKind.OTHER -> {
                    append("The sandbox command or file-writing tool copies this file into `inbox/` and returns its exact path. ")
                    append("Read it there with `runCommand` instead of asking the user to resend it.")
                }
            }
        },
    )

private fun formatFileSize(bytes: Long): String =

    when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(Locale.ROOT, bytes / (1024.0 * 1024))
        bytes >= 1024 -> "%.0f KB".format(Locale.ROOT, bytes / 1024.0)
        else -> "$bytes B"
    }

/** Several media items sent as one message, and which tools get to see which of them. */
internal fun albumContextBlock(
    itemCount: Int,
    photoCount: Int,
    videoCount: Int,
    attachedFiles: List<AttachedFile>,
): String {
    val attachedImages = attachedFiles.count { it.kind == AttachedFileKind.IMAGE }

    return xmlBlock(
        "album",
        buildString {
            append("User sent an album of $itemCount media item(s): $photoCount photo(s), $videoCount video(s). ")

            when {
                attachedFiles.isEmpty() ->
                    append("None of the items is available as an attached file; ")

                attachedImages > 1 ->
                    append(
                        "All $attachedImages images are attached at once, and `editImage` works on them " +
                                "together — combining them, putting one into another, building a collage. " +
                                "Every other tool sees only the first item; ",
                    )

                else -> append("Only the first item, `${attachedFiles.first().name}`, is attached; ")
            }

            append("mention this if the request depends on the other items.")
        },
    )
}

// a structured message flattened back into markdown never passes the sanitizing ordinary text does.
internal fun wrapRichMessage(markdown: String): String =
    xmlBlock("rich_message", markdown.neutralizePromptBlocks())

internal fun wrapAudioTranscript(text: String): String =
    xmlBlock("audio_transcript", text.neutralizePromptBlocks())

/** A button the model offered, pressed: the question it asked and the option chosen. */
internal fun inlineChoiceInput(question: String, option: String): String =
    xmlBlock(
        "inline_choice",
        buildString {
            appendLine(xmlBlock("question", question))
            append(xmlBlock("selected_option", option))
        },
    )
