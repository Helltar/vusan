package com.helltar.vusan.tools.tgchannel

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramChannelParserTest {

    private fun parse(html: String, maxPosts: Int = 20) =
        TelegramChannelParser.parse(
            html = html,
            username = "example_channel",
            url = "https://t.me/s/example_channel",
            maxPosts = maxPosts
        )

    @Test
    fun `parse returns posts newest first and keeps their timestamps`() {
        val page = parse(
            channelPage(
                posts = listOf(
                    channelPost(id = 100, text = "Oldest note", at = "2026-03-01T10:00:00+00:00"),
                    channelPost(id = 101, text = "Middle note", at = "2026-03-02T10:00:00+00:00"),
                    channelPost(id = 102, text = "Newest note", at = "2026-03-03T10:00:00+00:00")
                )
            ),
            maxPosts = 2
        )

        assertEquals("Example Channel", page.title)
        assertEquals(listOf("102", "101"), page.posts.map { it.id })
        assertEquals(Instant.parse("2026-03-03T10:00:00Z"), page.posts.first().postedAt)
    }

    // the widget marks the quoted preview of the replied-to post with the same _text class, and
    // puts it first, so a selector that ignores js-message_text reports the quote as the post.
    @Test
    fun `parse reads the post body rather than the quote of the post it replies to`() {
        val page = parse(
            channelPage(
                posts = listOf(
                    channelPost(
                        id = 200,
                        text = "Our own follow-up statement",
                        replyQuote = "The earlier bulletin everyone is replying to"
                    )
                )
            )
        )

        val post = page.posts.single()

        assertEquals("Our own follow-up statement", post.text)
        assertEquals("Example Channel: The earlier bulletin everyone is replying to", post.replyTo)
    }

    @Test
    fun `parse sums reactions and expands compact counts`() {
        val page = parse(
            channelPage(
                posts = listOf(
                    channelPost(
                        id = 300,
                        text = "Release notes",
                        reactions = listOf("A" to "1.2K", "B" to "340", "C" to "1.5M", "D" to "7")
                    )
                )
            )
        )

        val post = page.posts.single()

        assertEquals(1_200 + 340 + 1_500_000 + 7, post.reactionCount)
        // only the strongest few are rendered, biggest first, and the tail is dropped
        assertEquals("C 1500000 A 1200 B 340", post.reactions)
    }

    @Test
    fun `parse counts custom and paid reactions but shows only readable glyphs`() {
        val html =
            channelPage(
                posts = listOf(channelPost(id = 310, text = "Announcement", reactions = listOf("A" to "40")))
            ).replace(
                """<span class="tgme_reaction"><i class="emoji"><b>A</b></i>40</span>""",
                """<span class="tgme_reaction"><i class="emoji"><b>A</b></i>40</span>""" +
                        """<span class="tgme_reaction"><tg-emoji emoji-id="123"></tg-emoji>1.5K</span>""" +
                        """<span class="tgme_reaction tgme_reaction_paid"><i class="icon icon-telegram-stars"></i>7</span>"""
            )

        val post = parse(html).posts.single()

        assertEquals(40 + 1_500 + 7, post.reactionCount)
        assertEquals("A 40 ⭐ 7", post.reactions)
    }

    @Test
    fun `parse labels media kinds and collects every album image`() {
        val page = parse(
            channelPage(
                posts = listOf(
                    channelPost(
                        id = 400,
                        text = "Gallery of the week",
                        photos = listOf("https://cdn.example.com/a.jpg", "https://cdn.example.com/b.jpg"),
                        videoThumb = "https://cdn.example.com/clip.jpg"
                    )
                )
            )
        )

        val post = page.posts.single()

        assertEquals(listOf("photo", "video"), post.mediaKinds)
        assertTrue(post.hasMedia)
        assertEquals(
            listOf("https://cdn.example.com/a.jpg", "https://cdn.example.com/b.jpg", "https://cdn.example.com/clip.jpg"),
            post.imageUrls
        )
    }

    @Test
    fun `parse keeps the forwarded source and the link preview`() {
        val page = parse(
            channelPage(
                posts = listOf(
                    channelPost(
                        id = 500,
                        text = "Worth a read",
                        forwardedFrom = "Partner Channel",
                        linkPreview = Triple("example.com", "Quarterly Summary", "What changed since the last update.")
                    )
                )
            )
        )

        val post = page.posts.single()

        assertEquals("Partner Channel", post.forwardedFrom)
        assertEquals("example.com — Quarterly Summary — What changed since the last update.", post.linkPreview)
    }

    @Test
    fun `parse takes the older-than cursor from the widget's own more link`() {
        val posts = listOf(channelPost(id = 600, text = "Anything"))

        assertEquals(517L, parse(channelPage(posts = posts, moreBefore = 517)).olderThanCursor)
        assertNull(parse(channelPage(posts = posts)).olderThanCursor)
    }

    @Test
    fun `parse keeps a media post that carries no text and drops an empty one`() {
        val page = parse(
            channelPage(
                posts = listOf(
                    channelPost(id = 700, photos = listOf("https://cdn.example.com/meme.jpg")),
                    channelPost(id = 701)
                )
            )
        )

        assertEquals(listOf("700"), page.posts.map { it.id })
        assertEquals("", page.posts.single().text)
    }

    @Test
    fun `parse turns line breaks into newlines and keeps body links`() {
        val page = parse(
            channelPage(
                posts = listOf(
                    channelPost(
                        id = 800,
                        text = """First line<br>second line with a <a href="https://example.com/plan">plan</a>"""
                    )
                )
            )
        )

        val post = page.posts.single()

        assertEquals("First line\nsecond line with a plan", post.text)
        assertContains(post.links, "https://example.com/plan")
    }
}
