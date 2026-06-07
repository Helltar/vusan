package com.helltar.vusan.tools.vision

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import com.helltar.vusan.request.AttachedFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisionToolsTest {

    @Test
    fun `describeImage returns no-image message when no image is attached`() = runBlocking {
        val executor = FakePromptExecutor()
        val tools = VisionTools(visionClient(executor), attachedFile = null)

        val result = tools.describeImage("text")

        assertEquals("No image is attached in this turn.", result)
        assertEquals(0, executor.callCount)
    }

    @Test
    fun `describeImage refuses a non-image attachment without calling vision`() = runBlocking {
        var loaded = false
        val file = attachedFile(isImage = false, name = "data.csv") {
            loaded = true
            byteArrayOf(1)
        }
        val executor = FakePromptExecutor()
        val tools = VisionTools(visionClient(executor), file)

        val result = tools.describeImage("")

        assertEquals("The attached file `data.csv` is not an image, so it can't be described visually.", result)
        assertEquals(false, loaded)
        assertEquals(0, executor.callCount)
    }

    @Test
    fun `describeImage returns oversize message before loading bytes when metadata is too large`() = runBlocking {
        var loaded = false
        val file = attachedFile(fileSizeBytes = (9 * 1024 * 1024).toLong()) {
            loaded = true
            byteArrayOf(1)
        }
        val executor = FakePromptExecutor()
        val tools = VisionTools(visionClient(executor), file)

        val result = tools.describeImage("objects")

        assertEquals("The image is too large for vision (9437184 bytes, limit 8388608).", result)
        assertEquals(false, loaded)
        assertEquals(0, executor.callCount)
    }

    @Test
    fun `describeImage runs the vision prompt with the focus and returns its text`() = runBlocking {
        val file = attachedFile { byteArrayOf(1, 2, 3) }
        val executor = FakePromptExecutor(response = "A cat on a chair.")
        val tools = VisionTools(visionClient(executor), file)

        val result = tools.describeImage("visible text")

        assertEquals("A cat on a chair.", result)
        assertEquals(1, executor.callCount)

        val promptText = executor.receivedPrompt?.messages.orEmpty().joinToString("\n") { it.textContent() }
        assertTrue("visible text" in promptText, "expected the user focus to be forwarded into the vision prompt")
    }

    private fun visionClient(executor: PromptExecutor): ImageVisionClient =
        ImageVisionClient(executor, LLModel(provider = LLMProvider.OpenAI, id = "test", capabilities = emptyList()))

    private fun attachedFile(
        fileSizeBytes: Long? = null,
        isImage: Boolean = true,
        name: String = "photo.jpg",
        loadBytes: suspend () -> ByteArray
    ): AttachedFile =
        AttachedFile(
            name = name,
            fileSizeBytes = fileSizeBytes,
            mimeType = "image/jpeg",
            isImage = isImage,
            caption = "caption",
            loadBytes = loadBytes
        )

    private class FakePromptExecutor(private val response: String = "description") : PromptExecutor() {

        var callCount = 0
            private set

        var receivedPrompt: Prompt? = null
            private set

        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            callCount++
            receivedPrompt = prompt
            return Message.Assistant(content = response, metaInfo = ResponseMetaInfo.Empty)
        }

        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
            error("executeStreaming not used in test")

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            error("moderate not used in test")

        override fun close() = Unit
    }
}
