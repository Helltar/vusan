package com.helltar.vusan.telegram.inbound

import com.fasterxml.jackson.databind.ObjectMapper
import com.helltar.vusan.request.AttachedFileKind
import java.lang.reflect.Proxy
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.generics.TelegramClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplyContextTest {

    @Test
    fun `formatAgentInput includes replied text before current user message`() {
        val prompt =
            formatAgentInput(
                currentMessageText = "summarize this article",
                repliedMessage = RepliedMessageSummary(type = "text", textOrCaption = "https://example.com/article/4034"),
                quotedFragment = null
            )

        assertTrue(prompt.contains("<reply_context>"))
        assertTrue(prompt.contains("- type: text"))
        assertTrue(prompt.contains("https://example.com/article/4034"))
        assertTrue(prompt.contains("</reply_context>"))
        assertTrue(prompt.contains("<user_message>"))
        assertTrue(prompt.contains("summarize this article"))
        assertTrue(prompt.contains("</user_message>"))
    }

    @Test
    fun `formatAgentInput handles media without caption`() {
        val prompt =
            formatAgentInput(
                currentMessageText = "what's in the photo?",
                repliedMessage = RepliedMessageSummary(
                    type = "photo",
                    textOrCaption = null,
                    metadata = listOf("file_id: abc123", "width: 1280", "height: 720")
                ),
                quotedFragment = null
            )

        assertTrue(prompt.contains("- type: photo"))
        assertTrue(prompt.contains("- metadata:\n  - file_id: abc123"))
        assertTrue(prompt.contains("  - width: 1280"))
        assertFalse(prompt.contains("<text_caption>"))
    }

    @Test
    fun `isReplyToOtherUser skips replies to the bot`() {
        assertFalse(isReplyToOtherUser(replyAuthorId = 123, botUserId = 123))
        assertTrue(isReplyToOtherUser(replyAuthorId = 456, botUserId = 123))
        assertTrue(isReplyToOtherUser(replyAuthorId = null, botUserId = 123))
    }

    @Test
    fun `formatConversationInput keeps compact replied text context`() {
        val historyText =
            formatConversationInput(
                currentMessageText = "summarize this article and send it as a markdown file",
                repliedMessage = RepliedMessageSummary(
                    type = "text",
                    textOrCaption = "https://example.com/article/4034",
                    metadata = listOf("file_id: file-1")
                ),
                quotedFragment = null
            )

        assertTrue(historyText.contains("<reply_context>"))
        assertTrue(historyText.contains("- type: text"))
        assertTrue(historyText.contains("- metadata:\n  - file_id: file-1"))
        assertTrue(historyText.contains("https://example.com/article/4034"))
        assertTrue(historyText.contains("<text_caption>"))
        assertTrue(historyText.contains("</text_caption>"))
        assertTrue(historyText.contains("</reply_context>"))
        assertTrue(historyText.contains("<user_message>"))
        assertTrue(historyText.contains("summarize this article"))
        assertTrue(historyText.contains("</user_message>"))
    }

    @Test
    fun `formatAgentInput keeps quoted and current text inside their xml blocks`() {
        val prompt =
            formatAgentInput(
                currentMessageText = "answer & continue",
                repliedMessage = RepliedMessageSummary(
                    type = "text",
                    textOrCaption = "quoted & content"
                ),
                quotedFragment = null
            )

        assertTrue(prompt.contains("<text_caption>\nquoted & content\n</text_caption>"))
        assertTrue(prompt.contains("<user_message>\nanswer & continue\n</user_message>"))
    }

    @Test
    fun `quotedFragmentOrNull reads the part the sender selected`() {
        val message =
            message(
                """
                "text": "what is this",
                "quote": {"text": "the second engine stage", "position": 147, "is_manual": true}
                """
            )

        val withoutQuote =
            message(
                """
                "text": "what is this"
                """
            )

        assertEquals("the second engine stage", message.quotedFragmentOrNull())
        assertNull(withoutQuote.quotedFragmentOrNull())
    }

    // a reply to the bot's own message carries no reply context — that message is already in the
    // history — so the fragment is the only thing saying which part the question is about.
    @Test
    fun `formatAgentInput carries a quoted fragment without a reply summary`() {
        val prompt =
            formatAgentInput(
                currentMessageText = "what is this",
                repliedMessage = null,
                quotedFragment = "the second engine stage"
            )

        assertFalse(prompt.contains("<reply_context>"))
        assertTrue(prompt.contains("<quoted_fragment>\nthe second engine stage\n</quoted_fragment>"))
        assertTrue(prompt.indexOf("</quoted_fragment>") < prompt.indexOf("<user_message>"))
    }

    @Test
    fun `formatAgentInput keeps a quoted fragment next to the replied message it came from`() {
        val prompt =
            formatAgentInput(
                currentMessageText = "what does this mean?",
                repliedMessage = RepliedMessageSummary(type = "text", textOrCaption = "one two three four"),
                quotedFragment = "three"
            )

        assertTrue(prompt.contains("<text_caption>\none two three four\n</text_caption>"))
        assertTrue(prompt.contains("<quoted_fragment>\nthree\n</quoted_fragment>"))
        assertTrue(prompt.indexOf("</reply_context>") < prompt.indexOf("<quoted_fragment>"))
    }

    @Test
    fun `a fragment covering the whole replied message is not repeated`() {
        val prompt =
            formatAgentInput(
                currentMessageText = "what does this mean?",
                repliedMessage = RepliedMessageSummary(type = "text", textOrCaption = "one two three"),
                quotedFragment = "one two three"
            )

        assertFalse(prompt.contains("<quoted_fragment>"))
    }

    @Test
    fun `plain input stays plain when nothing is replied to or quoted`() {
        assertEquals("hello", formatAgentInput("hello", repliedMessage = null, quotedFragment = null))
        assertEquals("hello", formatConversationInput("hello", repliedMessage = null, quotedFragment = null))
    }

    @Test
    fun `a quoted fragment is stored with the history entry`() {
        val historyText =
            formatConversationInput(
                currentMessageText = "what is this",
                repliedMessage = null,
                quotedFragment = "the second engine stage"
            )

        assertTrue(historyText.contains("<quoted_fragment>\nthe second engine stage\n</quoted_fragment>"))
    }

    @Test
    fun `toAttachedFileOrNull maps a video with its duration and preview frame`() {
        val message =
            message(
                """
                "caption": "look at this",
                "video": {
                  "file_id": "video-1", "file_unique_id": "u1", "width": 1280, "height": 720,
                  "duration": 42, "mime_type": "video/mp4", "file_name": "clip.mp4", "file_size": 2000000,
                  "thumbnail": {"file_id": "thumb-1", "file_unique_id": "t1", "width": 320, "height": 180}
                }
                """
            )

        val file = assertNotNull(message.toAttachedFileOrNull(unusedClient))

        assertEquals(AttachedFileKind.VIDEO, file.kind)
        assertEquals("clip.mp4", file.name)
        assertEquals(42, file.durationSeconds)
        assertEquals(2_000_000L, file.fileSizeBytes)
        assertEquals("look at this", file.caption)
        assertNotNull(file.loadThumbnailBytes)
    }

    // telegram sets both `animation` and `document` on a gif, and the document copy must not win.
    @Test
    fun `toAttachedFileOrNull maps a gif as a video`() {
        val message =
            message(
                """
                "animation": {
                  "file_id": "anim-1", "file_unique_id": "u2", "width": 480, "height": 270,
                  "duration": 3, "mime_type": "video/mp4", "file_size": 90000
                },
                "document": {"file_id": "anim-1", "file_unique_id": "u2", "mime_type": "video/mp4"}
                """
            )

        val file = assertNotNull(message.toAttachedFileOrNull(unusedClient))

        assertEquals(AttachedFileKind.VIDEO, file.kind)
        assertEquals("video-u2.mp4", file.name)
        assertEquals(3, file.durationSeconds)
        assertTrue(file.isAnimation)
    }

    // a caption replaces the gif-as-reaction prompt, so the attachment block is what has to carry the
    // rule that a gif is not something to narrate back.
    @Test
    fun `attachedFileContextBlock marks a gif as a reaction, not a video to review`() {
        val message =
            message(
                """
                "animation": {
                  "file_id": "anim-1", "file_unique_id": "u2", "width": 480, "height": 270,
                  "duration": 3, "mime_type": "video/mp4", "file_size": 90000
                },
                "caption": "haha"
                """
            )

        val block = attachedFileContextBlock(assertNotNull(message.toAttachedFileOrNull(unusedClient)))

        assertTrue(block.contains("It is a GIF"))
        assertTrue(block.contains("never narrate it unasked"))
        assertFalse(block.contains("what is said in it"))
    }

    @Test
    fun `toAttachedFileOrNull maps a video note`() {
        val message =
            message(
                """
                "video_note": {
                  "file_id": "note-1", "file_unique_id": "u3", "length": 384, "duration": 7, "file_size": 120000
                }
                """
            )

        val file = assertNotNull(message.toAttachedFileOrNull(unusedClient))

        assertEquals(AttachedFileKind.VIDEO, file.kind)
        assertEquals("video-note-u3.mp4", file.name)
        assertEquals(7, file.durationSeconds)
        assertEquals(120_000L, file.fileSizeBytes)
    }

    @Test
    fun `toAttachedFileOrNull maps a video document without a duration`() {
        val message =
            message(
                """
                "document": {
                  "file_id": "doc-1", "file_unique_id": "u4", "file_name": "render.mkv",
                  "mime_type": "video/x-matroska", "file_size": 30000000,
                  "thumbnail": {"file_id": "thumb-2", "file_unique_id": "t2", "width": 320, "height": 180}
                }
                """
            )

        val file = assertNotNull(message.toAttachedFileOrNull(unusedClient))

        assertEquals(AttachedFileKind.VIDEO, file.kind)
        assertEquals("render.mkv", file.name)
        assertNull(file.durationSeconds)
        assertNotNull(file.loadThumbnailBytes)
    }

    @Test
    fun `toAttachedFileOrNull keeps a photo an image`() {
        val message =
            message(
                """
                "photo": [
                  {"file_id": "small", "file_unique_id": "p1", "width": 90, "height": 60, "file_size": 900},
                  {"file_id": "big", "file_unique_id": "p2", "width": 1280, "height": 720, "file_size": 90000}
                ]
                """
            )

        val file = assertNotNull(message.toAttachedFileOrNull(unusedClient))

        assertEquals(AttachedFileKind.IMAGE, file.kind)
        assertEquals("photo.jpg", file.name)
        assertNull(file.durationSeconds)
        assertNull(file.loadThumbnailBytes)
    }

    @Test
    fun `attachedFileContextBlock sends a video to describeVideo and not to the sandbox`() {
        val message =
            message(
                """
                "video": {
                  "file_id": "video-1", "file_unique_id": "u1", "duration": 42,
                  "mime_type": "video/mp4", "file_name": "clip.mp4", "file_size": 2000000
                }
                """
            )

        val block = attachedFileContextBlock(assertNotNull(message.toAttachedFileOrNull(unusedClient)))

        assertTrue(block.contains("name: clip.mp4"))
        assertTrue(block.contains("duration: 42s"))
        assertTrue(block.contains("`describeVideo`"))
        assertFalse(block.contains("codeExecution working directory"))
    }

    private val mapper = ObjectMapper()

    // attachment building only captures file ids; nothing is downloaded until the tool asks for bytes.
    private val unusedClient: TelegramClient =
        Proxy.newProxyInstance(
            TelegramClient::class.java.classLoader,
            arrayOf(TelegramClient::class.java)
        ) { _, method, _ -> error("unexpected client call: ${method.name}") } as TelegramClient

    // `date` must stay non-zero: the bot api models a zero date as an InaccessibleMessage subtype.
    private fun message(fields: String): Message =
        mapper.readValue(
            """{"message_id": 1, "date": 1774000000, "chat": {"id": 10, "type": "private"}, ${fields.trim()}}""",
            Message::class.java
        )
}
