package com.helltar.vusan.telegram.inbound

import org.telegram.telegrambots.meta.api.objects.richblock.*
import org.telegram.telegrambots.meta.api.objects.richtext.*

private const val MAX_HEADING_SIZE = 6

// a rich message never arrives as `text`: telegram delivers it as a block tree. it is flattened
// back into the rich Markdown dialect the agent itself writes for `sendRichMessage`, so a quoted
// or incoming rich message reads to the model exactly like one it would have produced.
internal fun RichMessage.toRichMarkdown(): String = blocks.renderBlocks()

// unknown block and rich text types deserialize to null (the bot api keeps adding them), and empty
// blocks would only add blank lines.
private fun List<RichBlock?>?.renderBlocks(): String =
    orEmpty().mapNotNull { it?.render()?.takeIf(String::isNotBlank) }.joinToString("\n\n")

private fun RichBlock.render(): String =
    when (this) {
        is RichBlockParagraph -> text.render()
        is RichBlockSectionHeading -> "${"#".repeat(size.coerceIn(1, MAX_HEADING_SIZE))} ${text.render()}"
        is RichBlockPreformatted -> "```${language.orEmpty()}\n${text.render()}\n```"
        is RichBlockFooter -> text.render()
        is RichBlockDivider -> "---"
        is RichBlockMathematicalExpression -> "```math\n$expression\n```"
        is RichBlockList -> items.joinToString("\n") { it.render() }
        is RichBlockBlockQuotation -> blocks.renderBlocks().quoted().withCredit(credit)
        is RichBlockPullQuotation -> text.render().quoted().withCredit(credit)
        is RichBlockDetails -> listOf(summary.render(), blocks.renderBlocks()).joinNonBlank("\n\n")
        is RichBlockCollage -> listOf(blocks.renderBlocks(), caption.render()).joinNonBlank("\n\n")
        is RichBlockSlideshow -> listOf(blocks.renderBlocks(), caption.render()).joinNonBlank("\n\n")
        is RichBlockTable -> renderTable()

        // media carries no text of its own, so only its caption reaches the model; a bare marker
        // such as `[photo]` would just get parroted back into replies.
        is RichBlockAnimation -> caption.render()
        is RichBlockAudio -> caption.render()
        is RichBlockPhoto -> caption.render()
        is RichBlockVideo -> caption.render()
        is RichBlockVoiceNote -> caption.render()
        is RichBlockMap -> caption.render()

        // anchors are invisible link targets, and `thinking` exists only in streamed drafts.
        else -> ""
    }

private fun RichBlockCaption?.render(): String = this?.let { it.text.render().withCredit(it.credit) }.orEmpty()

private fun RichBlockListItem.render(): String {
    val marker =
        when {
            hasCheckbox == true -> if (isChecked == true) "- [x]" else "- [ ]"
            else -> label.takeIf { it.isNotBlank() } ?: "-"
        }

    // continuation lines are indented so nested content stays inside the item.
    return "$marker ${blocks.renderBlocks().replace("\n", "\n  ")}".trimEnd()
}

private fun RichBlockTable.renderTable(): String {
    val rows = cells.map { row -> row.orEmpty().map { it.text.render() } }
    if (rows.isEmpty()) return caption.render()

    val body = rows.map { row -> row.joinToString(" | ", prefix = "| ", postfix = " |") }
    val separator = List(rows.first().size) { "---" }.joinToString(" | ", prefix = "| ", postfix = " |")
    val header = cells.first().orEmpty().any { it.isHeader == true }

    return listOf(
        if (header) (listOf(body.first(), separator) + body.drop(1)).joinToString("\n") else body.joinToString("\n"),
        caption.render()
    ).joinNonBlank("\n\n")
}

private fun RichText?.render(): String =
    when (this) {
        null -> ""
        is RichTextPlain -> text
        is RichTextConcat -> texts.joinToString("") { it.render() }
        is RichTextBold -> "**${text.render()}**"
        is RichTextItalic -> "*${text.render()}*"
        is RichTextUnderline -> "<u>${text.render()}</u>"
        is RichTextStrikethrough -> "~~${text.render()}~~"
        is RichTextSpoiler -> "||${text.render()}||"
        is RichTextMarked -> "==${text.render()}=="
        is RichTextCode -> "`${text.render()}`"
        is RichTextSubscript -> "<sub>${text.render()}</sub>"
        is RichTextSuperscript -> "<sup>${text.render()}</sup>"
        is RichTextUrl -> "[${text.render()}]($url)"
        is RichTextTextMention -> "[${text.render()}](tg://user?id=${user.id})"
        is RichTextCustomEmoji -> alternativeText
        is RichTextMathematicalExpression -> "$$expression$"

        // entity-like spans already read as themselves (`@name`, `#tag`, `/cmd`, the number), and
        // date-time, anchor links and references only wrap their own text.
        is RichTextDateTime -> text.render()
        is RichTextMention -> text.render()
        is RichTextHashtag -> text.render()
        is RichTextCashtag -> text.render()
        is RichTextBotCommand -> text.render()
        is RichTextEmailAddress -> text.render()
        is RichTextPhoneNumber -> text.render()
        is RichTextBankCardNumber -> text.render()
        is RichTextAnchorLink -> text.render()
        is RichTextReference -> text.render()
        is RichTextReferenceLink -> text.render()

        else -> ""
    }

private fun String.quoted(): String = lines().joinToString("\n") { "> $it" }

private fun String.withCredit(credit: RichText?): String =
    listOf(this, credit.render().takeIf { it.isNotBlank() }?.let { "— $it" }.orEmpty()).joinNonBlank("\n")

private fun List<String>.joinNonBlank(separator: String): String =
    filter { it.isNotBlank() }.joinToString(separator)
