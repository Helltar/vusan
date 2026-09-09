package com.helltar.vusan

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.fail

/**
 * How much of the codebase still knows that Telegram exists.
 *
 * A second messenger stays cheap only while the shared core is unaware of the first one. No import is
 * not proof of neutrality, but a new import is proof of regrowth — so this walks every package outside
 * `telegram/` and fails on one, carrying today's offenders as a list that may only shrink.
 *
 * The list is the progress report for the platform work: nothing else records how far it has got.
 *
 * **What this does not catch.** It reads imports, so a shared file can still depend on a messenger
 * *through another type* and pass: `AgentRunner` held a `StickerCatalog`, which holds a
 * `TelegramClient`, while importing nothing from `telegram/` itself. Three such dependencies were
 * found by reading the code, not by this test. A green run means no shared file names Telegram — not
 * that none reaches it. When adding a messenger, check what the constructors actually take.
 */
class PlatformBoundaryTest {

    private companion object {
        val TELEGRAM_IMPORT = Regex("""^import\s+(org\.telegram\.|com\.helltar\.vusan\.telegram\.)""")

        // the composition root wires whichever adapters the deployment runs, so it is expected to name
        // them. it is exempt rather than allowlisted: it will still import Telegram once nothing else does.
        val COMPOSITION_ROOT = setOf("src/main/kotlin/com/helltar/vusan/Main.kt")

        /**
         * Shared files that still reach into Telegram, and what each is waiting for. Removing an entry is
         * the point; adding one means the boundary moved the wrong way and needs a deliberate decision.
         */
        val ALLOWED = mapOf(
            "src/main/kotlin/com/helltar/vusan/tools/ToolRegistryFactory.kt" to
                    "holds the bot's TelegramClient so file-id tools can be built",
            "src/main/kotlin/com/helltar/vusan/tools/files/ChatFileTools.kt" to
                    "resends a file by Telegram's own file_id, which no other messenger has",
            "src/main/kotlin/com/helltar/vusan/tools/sticker/StickerCatalog.kt" to
                    "sticker sets are learned and resent by file_id, which is Telegram's own model",
            "src/test/kotlin/com/helltar/vusan/tools/files/ChatFileToolsTest.kt" to
                    "drives sendChatFile against a fake TelegramClient",
            "src/test/kotlin/com/helltar/vusan/tools/sticker/StickerCatalogTest.kt" to
                    "drives the catalog against a fake TelegramClient"
        )
    }

    @Test
    fun `only the listed shared files know about Telegram`() {
        val root = projectRoot()
        val offenders =
            sequenceOf("src/main/kotlin", "src/test/kotlin")
                .map(root::resolve)
                .flatMap { it.kotlinFiles() }
                .filterNot { "/telegram/" in it.pathIn(root) }
                .filter { it.hasTelegramImport() }
                .map { it.pathIn(root) }
                .toSet()

        val regrown = offenders - ALLOWED.keys - COMPOSITION_ROOT
        val cleaned = ALLOWED.keys - offenders

        if (regrown.isNotEmpty()) {
            fail(
                "these files outside telegram/ started importing Telegram:\n" +
                        regrown.sorted().joinToString("\n") { "  $it" } +
                        "\n\nkeep the shared core unaware of one messenger, or add the file to " +
                        "PlatformBoundaryTest.ALLOWED with the reason it cannot be."
            )
        }

        if (cleaned.isNotEmpty()) {
            fail(
                "these files no longer import Telegram — drop them from PlatformBoundaryTest.ALLOWED:\n" +
                        cleaned.sorted().joinToString("\n") { "  $it" }
            )
        }
    }

    private fun Path.kotlinFiles(): Sequence<Path> =
        if (exists()) walk().filter { it.isRegularFile() && it.extension == "kt" } else emptySequence()

    private fun Path.hasTelegramImport(): Boolean =
        readLines().any { TELEGRAM_IMPORT.containsMatchIn(it) }

    private fun Path.pathIn(root: Path): String = relativeTo(root).toString()

    // the test's working directory is the project directory under gradle, but not under every ide runner.
    private fun projectRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull { it.resolve("src/main/kotlin").exists() }
            ?: fail("could not locate the project root from ${Path.of("").toAbsolutePath()}")
}
