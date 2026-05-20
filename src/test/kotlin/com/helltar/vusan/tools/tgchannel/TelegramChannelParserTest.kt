package com.helltar.vusan.tools.tgchannel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramChannelParserTest {

    @Test
    fun `parse extracts recent posts newest first`() {
        val page = TelegramChannelParser.parse(
            html = sampleHtml(),
            username = "example_channel",
            url = "https://t.me/s/example_channel",
            maxPosts = 2
        )

        assertEquals("TelegramChannel", page.title)
        assertEquals(2, page.posts.size)
        assertEquals("102", page.posts[0].id)
        assertEquals("101", page.posts[1].id)
        assertTrue(page.posts[0].hasMedia)
        assertEquals("Second post\nwith a link", page.posts[0].text)
        assertEquals(listOf("https://cdn.example.com/photo.jpg"), page.posts[0].imageUrls)
        assertEquals(listOf("https://example.com/roadmap"), page.posts[0].links)
    }

    private fun sampleHtml(): String =
        """
        <html>
          <head><meta property="og:title" content="TelegramChannel"></head>
          <body>
            <div class="tgme_widget_message" data-post="example_channel/100">
              <div class="tgme_widget_message_text">Old post</div>
              <a class="tgme_widget_message_date" href="https://t.me/example_channel/100"><time datetime="2026-01-01T10:00:00+00:00"></time></a>
            </div>
            <div class="tgme_widget_message" data-post="example_channel/101">
              <div class="tgme_widget_message_text">First post</div>
              <span class="tgme_widget_message_views">123</span>
              <a class="tgme_widget_message_date" href="https://t.me/example_channel/101"><time datetime="2026-01-02T10:00:00+00:00"></time></a>
            </div>
            <div class="tgme_widget_message" data-post="example_channel/102">
              <a class="tgme_widget_message_photo_wrap" style="background-image:url('https://cdn.example.com/photo.jpg')" href="https://t.me/example_channel/102"></a>
              <div class="tgme_widget_message_text">Second post<br>with a <a href="https://example.com/roadmap">link</a></div>
              <a class="tgme_widget_message_date" href="https://t.me/example_channel/102"><time datetime="2026-01-03T10:00:00+00:00"></time></a>
            </div>
          </body>
        </html>
        """.trimIndent()
}
