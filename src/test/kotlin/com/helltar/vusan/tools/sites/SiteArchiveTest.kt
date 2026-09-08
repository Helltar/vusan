package com.helltar.vusan.tools.sites

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SiteArchiveTest {
    private val limits = SiteLimits(files = 5, fileBytes = 1024, totalBytes = 4096, pathDepth = 3)

    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun read(archive: ByteArray): Pair<SiteArchiveSummary, List<String>> = runBlocking {
        val paths = mutableListOf<String>()
        val summary = readSiteArchive(archive, limits) { path, _ -> paths += path }
        summary to paths
    }

    private fun text(value: String) = value.toByteArray()

    @Test
    fun `a plain site keeps its layout and reports the entry page`() {
        val (summary, paths) = read(
            archive(
                "index.html" to text("<h1>hello</h1>"),
                "assets/game.js" to text("run()"),
                "assets/sprites/hero.png" to text("png")
            )
        )
        assertEquals(listOf("index.html", "assets/game.js", "assets/sprites/hero.png"), paths)
        assertEquals(3, summary.files)
        assertEquals(22L, summary.bytes)
        assertTrue(summary.hasRootIndex)
    }

    @Test
    fun `directory entries and a leading dot slash are not published as files`() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("assets/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("./index.html"))
            zip.write(text("page"))
            zip.closeEntry()
        }
        val (summary, paths) = read(bytes.toByteArray())
        assertEquals(listOf("index.html"), paths)
        assertTrue(summary.hasRootIndex)
    }

    @Test
    fun `an archive of the directory rather than its contents is reported, not silently broken`() {
        val (summary, _) = read(archive("site/index.html" to text("page")))
        assertFalse(summary.hasRootIndex)
    }

    @Test
    fun `paths that escape the site or hide a dotfile are refused`() {
        val refused = listOf(
            "../escape.html",
            "assets/../../etc/passwd",
            "/etc/passwd",
            ".git/config",
            "assets/.env",
            "assets\\game.js",
            "a/b/c/d/too-deep.html",
            "x".repeat(200) + ".html"
        )
        refused.forEach { name ->
            assertFailsWith<IllegalArgumentException>(name) { read(archive(name to text("x"))) }
        }
    }

    @Test
    fun `the file count, one file's size and the whole site's size are all capped`() {
        val many = (1..6).map { "page$it.html" to text("x") }.toTypedArray()
        assertContains(assertFailsWith<IllegalArgumentException> { read(archive(*many)) }.message.orEmpty(), "at most 5 files")

        val big = assertFailsWith<IllegalArgumentException> { read(archive("big.bin" to ByteArray(1025))) }
        assertContains(big.message.orEmpty(), "per-file limit")

        val total = assertFailsWith<IllegalArgumentException> {
            read(archive(*(1..5).map { "page$it.html" to ByteArray(1024) }.toTypedArray()))
        }
        assertContains(total.message.orEmpty(), "larger than the")
    }

    @Test
    fun `an archive with nothing publishable in it is refused`() {
        assertFailsWith<IllegalArgumentException> { read(archive()) }
        assertContains(
            assertFailsWith<IllegalArgumentException> {
                read(archive("index.html" to text("one"), "./index.html" to text("two")))
            }.message.orEmpty(),
            "twice"
        )
    }
}
