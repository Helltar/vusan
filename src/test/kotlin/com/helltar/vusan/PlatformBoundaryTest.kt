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
 * How much of the codebase still knows telegram exists.
 *
 * A second messenger stays cheap only while the shared core is unaware of the first. No import is no proof of
 * neutrality, but a new import is proof of regrowth, so this walks every package outside `telegram/` and
 * fails on one. The exception list is empty, the state to hold; what remains is semantics (telegram's
 * formatting, limits, numeric message references) that no import test sees.
 *
 * **What this does not catch.** A shared file can depend on a messenger *through another type* and pass:
 * `AgentRunner` once held a `StickerCatalog` holding a `TelegramClient` while importing nothing from
 * `telegram/`. A green run means no shared file names telegram, not that none reaches it; when adding a
 * messenger, check what the constructors actually take.
 */
class PlatformBoundaryTest {

    @Test
    fun `nothing outside the telegram adapter knows about Telegram`() {
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
                        "PlatformBoundaryTest.ALLOWED with the reason it cannot be.",
            )
        }

        if (cleaned.isNotEmpty()) {
            fail(
                "these files no longer import Telegram — drop them from PlatformBoundaryTest.ALLOWED:\n" +
                        cleaned.sorted().joinToString("\n") { "  $it" },
            )
        }
    }

    private fun Path.kotlinFiles(): Sequence<Path> =
        if (exists()) walk().filter { it.isRegularFile() && it.extension == "kt" } else emptySequence()

    private fun Path.hasTelegramImport(): Boolean =
        readLines().any { TELEGRAM_IMPORT.containsMatchIn(it) }

    private fun Path.pathIn(root: Path): String = relativeTo(root).toString()

    // the working directory is the project under gradle, but not under every ide runner.
    private fun projectRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull { it.resolve("src/main/kotlin").exists() }
            ?: fail("could not locate the project root from ${Path.of("").toAbsolutePath()}")

    private companion object {
        val TELEGRAM_IMPORT = Regex("""^import\s+(org\.telegram\.|com\.helltar\.vusan\.telegram\.)""")

        // the composition root wires the adapters, so it is expected to name them: exempt rather than
        // allowlisted.
        val COMPOSITION_ROOT = setOf("src/main/kotlin/com/helltar/vusan/Main.kt")

        /**
         * Shared files still reaching into telegram, and what each waits for. Empty, the state to hold: a new
         * entry means the boundary moved the wrong way. A tool only one messenger can implement belongs in
         * its adapter and reaches the registry through `PlatformToolSets`.
         */
        val ALLOWED = emptyMap<String, String>()
    }
}
