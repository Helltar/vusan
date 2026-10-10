package com.helltar.vusan.agent

import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import com.helltar.vusan.tools.keepOnShelf
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val START: Instant = Instant.parse("2026-10-10T09:30:00Z")

// named the way the shelf names it, in whatever zone the tests run in
private val DIRECTORY = "turns/" + DateTimeFormatter.ofPattern("yyMMdd-HHmmss").format(START.atZone(ZoneId.systemDefault()))

private class FakeSandbox(
    private val content: ByteArray = "hello there".encodeToByteArray(),
    private val existing: List<String> = emptyList(),
    private val refused: String? = null,
) : ShelfSandbox {

    val reads = mutableListOf<String>()
    val written = mutableListOf<String>()
    val deleted = mutableListOf<String>()

    override suspend fun read(path: String, maxBytes: Int): ByteArray {
        if (content.size > maxBytes) throw ShelfSandbox.FileTooLarge(path, maxBytes)

        reads += path

        return content
    }

    override suspend fun write(path: String, bytes: ByteArray) {
        require(refused == null || !path.endsWith(refused)) { "too large" }
        written += path
    }

    override suspend fun directories(path: String): List<String> = existing

    override suspend fun deleteDirectory(path: String) {
        deleted += path
    }
}

class TurnShelfTest {

    @Test
    fun `a later call takes an earlier result whole by its label`() = runBlocking {
        val shelf = TurnShelf()
        shelf.open().close("a long answer")
        val second = shelf.open()

        assertEquals("a long answer", second.textOrNull("#1"))
        assertEquals("a long answer", second.textOrNull(" #1 "))
    }

    // a reference is a value, not a template: text that merely mentions a label is meant as written
    @Test
    fun `only a whole value is a reference`() = runBlocking {
        val shelf = TurnShelf()
        shelf.open().close("a long answer")
        val second = shelf.open()

        assertNull(second.textOrNull("see #1"))
        assertNull(second.textOrNull("plain words"))
    }

    @Test
    fun `a call cannot take itself or anything after it`() = runBlocking {
        val shelf = TurnShelf()
        val first = shelf.open()
        shelf.open()

        assertContains(assertFailsWith<IllegalArgumentException> { first.textOrNull("#1") }.message.orEmpty(), "came before it")
        assertFailsWith<IllegalArgumentException> { first.textOrNull("#2") }
    }

    @Test
    fun `a file a call kept is named by its label and listed under its result`() = runBlocking {
        val shelf = TurnShelf()
        val maker = shelf.open()
        val label = withContext(maker) { keepOnShelf("cat.png", byteArrayOf(1, 2, 3)) }
        maker.close("drew a cat")

        assertEquals("#1/1", label)
        assertEquals("[#1] drew a cat\n[#1/1] cat.png — image, 3 B", maker.labeled("drew a cat"))

        val file = shelf.open().file("#1/1")

        assertEquals("cat.png", file.name)
        assertEquals(AttachedFileKind.IMAGE, file.kind)
        assertContentEquals(byteArrayOf(1, 2, 3), file.loadBytes())
    }

    // a cut result is still whole on the shelf, and the model has to know where
    @Test
    fun `a result the budget cut says where the whole of it is`() {
        val call = TurnShelf().open()

        assertEquals("[#1] the head\n[`#1` still holds the whole result for any argument that takes a label]", call.labeled("the head", cut = true))
    }

    @Test
    fun `the request's own files are the files of call zero`() = runBlocking {
        val shelf = TurnShelf(listOf(attachment("first.jpg"), attachment("second.jpg")))
        val call = shelf.open()

        assertEquals("second.jpg", call.file("#0/2").name)
        assertFailsWith<IllegalArgumentException> { call.file("#0/3") }
        assertFailsWith<IllegalArgumentException> { call.textOrNull("#0") }
    }

    @Test
    fun `a result is not a file, and a file of a call that made none does not exist`() = runBlocking {
        val shelf = TurnShelf()
        shelf.open().close("only words")
        val second = shelf.open()

        assertContains(assertFailsWith<IllegalArgumentException> { second.file("#1") }.message.orEmpty(), "`#1/1`")
        assertContains(assertFailsWith<IllegalArgumentException> { second.file("#1/1") }.message.orEmpty(), "made none")
        assertFailsWith<IllegalArgumentException> { second.file("cat.png") }
    }

    @Test
    fun `a sandbox path reads through the turn's sandbox, as a file or as text`() = runBlocking {
        val shelf = TurnShelf()
        val sandbox = FakeSandbox()
        shelf.connectSandbox(sandbox)
        val call = shelf.open()

        assertEquals("hello there", call.textOrNull("sandbox:notes/draft.txt"))

        val clip = call.file("sandbox:out/clip.mp4")

        assertEquals("clip.mp4", clip.name)
        assertEquals(AttachedFileKind.VIDEO, clip.kind)
        assertEquals(listOf("notes/draft.txt"), sandbox.reads, "a named file is read only when its bytes are wanted")

        clip.loadBytes()

        assertEquals(listOf("notes/draft.txt", "out/clip.mp4"), sandbox.reads)
    }

    // a large artifact named as text is refused under the text cap, and none of it is moved first
    @Test
    fun `a sandbox file over the text cap is refused before it is read, while a file argument still takes it`() = runBlocking {
        val shelf = TurnShelf()
        val sandbox = FakeSandbox(content = ByteArray(2 * 1024 * 1024) { 'a'.code.toByte() })
        shelf.connectSandbox(sandbox)
        val call = shelf.open()

        val message = assertFailsWith<IllegalArgumentException> { call.textOrNull("sandbox:build/app.jar") }.message.orEmpty()

        assertContains(message, "1 MB")
        assertContains(message, "cannot stand in for text")
        assertTrue(sandbox.reads.isEmpty())
        assertEquals(2 * 1024 * 1024, call.file("sandbox:build/app.jar").loadBytes().size)
    }

    @Test
    fun `a binary file does not stand in for text`() = runBlocking {
        val shelf = TurnShelf()
        shelf.connectSandbox(FakeSandbox(content = byteArrayOf(0x50, 0, 0x4e, 0x47)))

        assertFailsWith<IllegalArgumentException> { shelf.open().textOrNull("sandbox:image.png") }
    }

    @Test
    fun `without a sandbox a sandbox path names nothing`() = runBlocking {
        val message = assertFailsWith<IllegalArgumentException> { TurnShelf().open().file("sandbox:out.mp4") }.message

        assertContains(message.orEmpty(), "no sandbox")
    }

    // calls of one read-only run go side by side, so a reference to an earlier one of them waits for it
    @Test
    fun `a reference to a call still running waits for it to finish`() = runBlocking {
        val shelf = TurnShelf()
        val first = shelf.open()
        val second = shelf.open()

        val waiting = async { second.textOrNull("#1") }
        yield()

        assertFalse(waiting.isCompleted)

        first.close("finished")

        assertEquals("finished", waiting.await())
    }

    @Test
    fun `nothing is kept outside a call of a turn`() = runBlocking {
        assertNull(keepOnShelf("cat.png", byteArrayOf(1)))
    }

    @Test
    fun `the sandbox gets the attachments, every file kept and the text of material answers, once each`() = runBlocking {
        val shelf = TurnShelf(listOf(attachment("photo.jpg")), startedAt = START)
        val sandbox = FakeSandbox()
        shelf.connectSandbox(sandbox)

        shelf.open().close("found three pages", "webSearch", copiesText = true)
        val maker = shelf.open()
        withContext(maker) { keepOnShelf("cat.png", byteArrayOf(1)) }
        maker.close("Image queued.", "generateImage")
        val running = shelf.open()

        val first = shelf.copyToSandbox()

        assertEquals(listOf("$DIRECTORY/00-1-photo.jpg", "$DIRECTORY/01-webSearch.txt", "$DIRECTORY/02-1-cat.png"), sandbox.written)
        assertEquals(sandbox.written.map { "`$it`" }, first)

        running.close("done", "lookUp", copiesText = true)
        shelf.copyToSandbox()

        assertEquals(listOf("$DIRECTORY/00-1-photo.jpg", "$DIRECTORY/01-webSearch.txt", "$DIRECTORY/02-1-cat.png", "$DIRECTORY/03-lookUp.txt"), sandbox.written)
    }

    @Test
    fun `the first copy of a turn keeps the two turns before it and removes the older ones`() = runBlocking {
        val shelf = TurnShelf(startedAt = START)
        val sandbox = FakeSandbox(existing = listOf("260104-090000", "260101-090000", "260103-090000", "260102-090000"))
        shelf.connectSandbox(sandbox)

        shelf.copyToSandbox()
        shelf.copyToSandbox()

        assertEquals(listOf("turns/260101-090000", "turns/260102-090000"), sandbox.deleted)
    }

    @Test
    fun `a write the sandbox refuses is reported and does not stop the rest`() = runBlocking {
        val shelf = TurnShelf(startedAt = START)
        shelf.connectSandbox(FakeSandbox(refused = ".mp4"))
        val maker = shelf.open()
        withContext(maker) {
            keepOnShelf("big.mp4", byteArrayOf(1))
            keepOnShelf("small.png", byteArrayOf(2))
        }
        maker.close("made two", "draw")

        val lines = shelf.copyToSandbox()

        assertEquals(listOf("`$DIRECTORY/01-1-big.mp4` could not be copied: too large", "`$DIRECTORY/01-2-small.png`"), lines)
    }

    @Test
    fun `without a sandbox nothing is copied`() = runBlocking {
        assertTrue(TurnShelf(listOf(attachment("photo.jpg"))).copyToSandbox().isEmpty())
    }

    private fun attachment(name: String) =
        AttachedFile(name = name, fileSizeBytes = 1, mimeType = "image/jpeg", kind = AttachedFileKind.IMAGE, loadBytes = { byteArrayOf(1) })
}
