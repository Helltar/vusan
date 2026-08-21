package com.helltar.vusan.tools.tgchannel

import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramChannelVisionSelectionTest {

    private fun post(id: String, text: String = "", images: Int = 1, reactions: Int = 0) =
        TelegramChannelPost(
            id = id,
            url = "https://t.me/example_channel/$id",
            postedAt = null,
            text = text,
            views = null,
            reactionCount = reactions,
            reactions = null,
            forwardedFrom = null,
            replyTo = null,
            linkPreview = null,
            mediaKinds = listOf("photo"),
            imageUrls = (1..images).map { "https://cdn.example.com/$id-$it.jpg" },
            links = emptyList()
        )

    private val longCaption = "A caption long enough to be carrying the post entirely on its own words. ".repeat(2)

    @Test
    fun `everything is described when the window fits the allowance`() {
        val posts = listOf(post("1"), post("2", images = 3), post("3"))

        assertEquals(listOf("1", "2", "3"), selectPostsForVision(posts, allowance = 10).map { it.id })
    }

    @Test
    fun `posts without media are never selected`() {
        val text = post("1").copy(mediaKinds = emptyList(), imageUrls = emptyList())

        assertEquals(listOf("2"), selectPostsForVision(listOf(text, post("2")), allowance = 10).map { it.id })
    }

    @Test
    fun `a tight allowance goes to the posts whose text cannot be carrying them`() {
        val posts =
            listOf(
                post("wordy", text = longCaption, reactions = 9_000),
                post("bare", reactions = 5),
                post("caption", text = "Short one", reactions = 10)
            )

        // "wordy" wins on reactions by far, but its own text already says what the post is about
        assertEquals(listOf("bare", "caption"), selectPostsForVision(posts, allowance = 2).map { it.id })
    }

    @Test
    fun `among equally text-poor posts the most reacted ones win`() {
        val posts = listOf(post("quiet", reactions = 1), post("loud", reactions = 900))

        assertEquals(listOf("loud"), selectPostsForVision(posts, allowance = 1).map { it.id })
    }

    @Test
    fun `an album is taken whole or skipped, never half`() {
        val posts = listOf(post("album", images = 3, reactions = 900), post("single", reactions = 1))

        // two permits cannot hold the three-image album, so the single-image post takes them instead
        assertEquals(listOf("single"), selectPostsForVision(posts, allowance = 2).map { it.id })
    }

    @Test
    fun `selection keeps reading order rather than ranking order`() {
        val posts = listOf(post("first", reactions = 1), post("second", reactions = 900))

        assertEquals(listOf("first", "second"), selectPostsForVision(posts, allowance = 2).map { it.id })
    }
}
