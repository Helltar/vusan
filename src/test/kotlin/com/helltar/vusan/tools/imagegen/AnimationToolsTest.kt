package com.helltar.vusan.tools.imagegen

import com.helltar.vusan.agent.TurnShelf
import com.helltar.vusan.config.ImageProviderConfig
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.outbox.BotOutput
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import com.helltar.vusan.request.ChatCapabilities
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnimationToolsTest {

    private val gif = byteArrayOf(0x47, 0x49, 0x46, 0x38)
    private val config = ImageProviderConfig.OpenAi(apiKey = "sk-test")
    private val likeness = SelfImage(SourceImage(byteArrayOf(1), "avatar.jpg", "image/jpeg"), appearance = null)

    // what the tool hands the provider: the instruction, the sources in order and the timing
    private inner class RecordingClient : ImageClient {
        var prompt: String? = null
        var sources: List<String>? = null
        var animation: ImageAnimation? = null

        override val maxEditImages = 16

        override suspend fun generate(prompt: String, framing: ImageFraming): ByteArray = error("unused")

        override suspend fun edit(prompt: String, images: List<SourceImage>, framing: ImageFraming): ByteArray =
            error("unused")

        override suspend fun animate(prompt: String, images: List<SourceImage>, animation: ImageAnimation): ByteArray {
            this.prompt = prompt
            this.sources = images.map { it.filename }
            this.animation = animation

            return gif
        }
    }

    private fun attachment(name: String) =
        AttachedFile(name = name, fileSizeBytes = null, mimeType = "image/png", kind = AttachedFileKind.IMAGE, loadBytes = { byteArrayOf(7) })

    private fun tools(
        client: ImageClient,
        outbox: BotOutbox = BotOutbox(),
        attached: List<AttachedFile> = emptyList(),
        selfImage: SelfImage? = null,
    ) = AnimationTools(client, config, outbox, attached, selfImage)

    @Test
    fun `a gif from a description is queued as an animation with the timing asked for`() = runBlocking {
        val client = RecordingClient()
        val outbox = BotOutbox()

        val result =
            withContext(TurnShelf().open()) {
                tools(client, outbox, attached = listOf(attachment("unrelated.png")))
                    .animateImage("a fox wagging its tail", frames = 6, frameDurationMs = 120)
            }

        val queued = assertIs<BotOutput.Animation>(outbox.pending.single().output)
        assertContentEquals(gif, queued.bytes)
        assertEquals("animation.gif", queued.filename)
        assertEquals(ImageAnimation(6, 120), client.animation)
        // an attachment is animated only when asked for
        assertEquals(emptyList(), client.sources)
        assertContains(result, "6 frames of 120 ms")
    }

    @Test
    fun `animateAttached brings the turn's pictures to life, with the bot's likeness first when it is in them`() = runBlocking {
        val client = RecordingClient()

        withContext(TurnShelf().open()) {
            tools(client, attached = listOf(attachment("cat.png")), selfImage = likeness)
                .animateImage("dancing with the cat", animateAttached = true, withYourself = true)
        }

        assertEquals(listOf("avatar.jpg", "cat.png"), client.sources)
        assertContains(requireNotNull(client.prompt), "The second image is the picture being edited.")
    }

    @Test
    fun `a gif of the bot alone animates its likeness`() = runBlocking {
        val client = RecordingClient()

        withContext(TurnShelf().open()) { tools(client, selfImage = likeness).animateImage("waving", withYourself = true) }

        assertEquals(listOf("avatar.jpg"), client.sources)
        assertContains(requireNotNull(client.prompt), "likeness sheet")
    }

    @Test
    fun `timing outside the provider's range is refused before anything is drawn`() = runBlocking {
        val client = RecordingClient()

        val tooMany = tools(client).animateImage("a blink", frames = 40)
        val tooFast = tools(client).animateImage("a blink", frameDurationMs = 5)

        assertContains(tooMany, "`frames` must be from 2 to 16")
        assertContains(tooFast, "`frameDurationMs` must be from 20 to 2000")
        assertEquals(null, client.animation)
    }

    @Test
    fun `a chat refusing gifs gets nothing drawn`() = runBlocking {
        val client = RecordingClient()
        val outbox = BotOutbox(ChatCapabilities(stickersAndAnimations = false))

        val result = tools(client, outbox).animateImage("a blink")

        assertContains(result, "does not accept GIFs")
        assertEquals(null, client.animation)
        assertTrue(outbox.pending.isEmpty())
    }
}
