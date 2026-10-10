package com.helltar.vusan.agent

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.common.sanitizeFilename
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import com.helltar.vusan.request.attachedFileKindOf
import com.helltar.vusan.request.isAnimationFile
import com.helltar.vusan.request.mimeTypeOfName
import com.helltar.vusan.tools.CallShelf
import com.helltar.vusan.tools.SANDBOX_REFERENCE_PREFIX
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Everything one turn's tools produced, under the labels the model reads, so that what one call made
 * another can take: `#3` is the text the third call answered with, `#5/1` the first file the fifth
 * made, `#0/1` the request's own attachment, and `sandbox:<path>` a file in the person's sandbox.
 *
 * The loop opens a [Call] for every tool call in batch order and runs the tool inside it; the call is
 * the [CallShelf] the argument decoder resolves against, and what a tool keeps lands under it. A
 * call may only take what came before it, so calls that run side by side never wait on each other in a
 * circle: a reference to an earlier call of the same batch waits for that call to finish.
 *
 * It lives for one turn and in memory only. Stored history carries the results without their labels,
 * so the next turn has nothing to point at that is no longer here. What reaches the sandbox stays there
 * a little longer, in a directory of the turn's own under `turns/`.
 */
class TurnShelf(
    private val attachments: List<AttachedFile> = emptyList(),
    startedAt: Instant = Instant.now(),
) {

    private val calls = mutableListOf<Call>()
    private var keptBytes = 0L

    // named by when the turn started, so the directories sort in the order the turns came
    private val directory = "$TURNS_DIRECTORY/" + STAMP.format(startedAt.atZone(ZoneId.systemDefault()))

    // copies run from whichever sandbox call comes next, and two of a batch must not write the same thing
    private val copying = Mutex()
    private var started = false

    // the person's sandbox, when this chat has one; set by whoever opens it for the turn
    @Volatile
    private var sandbox: ShelfSandbox? = null

    /** Lets `sandbox:` references read from the turn's sandbox, and the turn's files reach it. */
    fun connectSandbox(sandbox: ShelfSandbox) {
        this.sandbox = sandbox
    }

    /** Opens the next call of the turn; the loop runs the tool inside it and closes it with what it answered. */
    fun open(): Call =
        synchronized(calls) { Call(calls.size + 1).also { calls += it } }

    /**
     * Writes into the turn's own directory in the sandbox, `turns/<when>/`, what has not reached it yet:
     * the request's attachments as `00-1-name` on the first copy, every file a finished call kept as
     * `05-1-name`, and the text of every answer its tool marks as material as `03-webSearch.txt`. The first
     * copy of a turn also removes all but the last few turns' directories. One line per thing written, or
     * why it was not; nothing without a sandbox.
     */
    suspend fun copyToSandbox(): List<String> {
        val sandbox = sandbox ?: return emptyList()

        return copying.withLock {
            val lines = mutableListOf<String>()

            if (!started) {
                started = true
                removeOldTurns(sandbox)
                attachments.forEachIndexed { index, file -> lines += copyAttachment(sandbox, index + 1, file) }
            }

            synchronized(calls) { calls.filter { it.finished && !it.copied } }.forEach { lines += it.copyInto(sandbox) }

            lines
        }
    }

    // a turn often picks up what the one before it was given or made, so a few stay; the rest would only
    // fill a home of a fixed size with downloads nobody will open again
    private suspend fun removeOldTurns(sandbox: ShelfSandbox) {
        val current = directory.substringAfterLast('/')

        runCatching {
            sandbox.directories(TURNS_DIRECTORY)
                .filter { it != current }
                .sorted()
                .dropLast(KEPT_TURNS - 1)
                .forEach { sandbox.deleteDirectory("$TURNS_DIRECTORY/$it") }
        }.onFailure {
            it.rethrowIfCancellation()
            log.warn { "old turn directories left in place: ${it.message}" }
        }
    }

    private suspend fun copyAttachment(sandbox: ShelfSandbox, index: Int, file: AttachedFile): String {
        val path = "$directory/00-$index-${file.name.sanitizeFilename().ifBlank { "attachment" }}"

        if ((file.fileSizeBytes ?: 0) > MAX_ATTACHMENT_BYTES) return "`${file.name}` is over the $MAX_ATTACHMENT_MB MB an attachment may bring in"

        return copy(sandbox, path) {
            file.loadBytes().also { require(it.size <= MAX_ATTACHMENT_BYTES) { "over the $MAX_ATTACHMENT_MB MB an attachment may bring in" } }
        }
    }

    // the turn's whole holding, across calls: downloads are the large ones
    private fun reserve(name: String, size: Int): Boolean =
        synchronized(calls) {
            if (keptBytes + size > MAX_KEPT_BYTES) {
                log.warn { "turn shelf full: kept=[$keptBytes] refused=[$size] name=[$name]" }
                return false
            }

            keptBytes += size
            true
        }

    private fun call(number: Int, from: Int): Call {
        require(number in 1 until from) {
            if (number >= from) "`#$number` is not an earlier result of this turn: a call can only take what came before it"
            else "`#0` holds the request's own files; name one as `#0/1`"
        }

        return synchronized(calls) { calls[number - 1] }
    }

    private suspend fun resolveText(value: String, from: Int): String? {
        val reference = value.trim()

        RESULT.matchEntire(reference)?.let { match ->
            return call(match.groupValues[1].toInt(), from).text()
        }

        val bytes =
            when {
                FILE.matches(reference) -> resolveFile(reference, from).loadBytes()

                // read under the text cap, so a build artifact named here is refused before any of it moves
                reference.startsWith(SANDBOX_REFERENCE_PREFIX) -> {
                    val (sandbox, path) = sandboxReference(reference)

                    try {
                        sandbox.read(path, MAX_TEXT_BYTES)
                    } catch (e: ShelfSandbox.FileTooLarge) {
                        throw IllegalArgumentException("${e.message}, so it cannot stand in for text; open it in the sandbox instead", e)
                    }
                }

                else -> return null
            }

        require(bytes.size <= MAX_TEXT_BYTES && bytes.none { it == 0.toByte() }) {
            "`$reference` is not a text file, so it cannot stand in for text; open it in the sandbox instead"
        }

        return bytes.decodeToString()
    }

    private suspend fun resolveFile(value: String, from: Int): AttachedFile {
        val reference = value.trim()

        FILE.matchEntire(reference)?.let { match ->
            val (number, index) = match.destructured
            val files = if (number.toInt() == 0) attachments else call(number.toInt(), from).files()

            return requireNotNull(files.getOrNull(index.toInt() - 1)) {
                "there is no file `$reference`" +
                        if (files.isEmpty()) "; `#$number` made none" else "; `#$number` has ${files.size}"
            }
        }

        if (reference.startsWith(SANDBOX_REFERENCE_PREFIX)) {
            val (sandbox, path) = sandboxReference(reference)

            return keptFile(path.substringAfterLast('/'), sizeBytes = null) { sandbox.read(path, MAX_FILE_BYTES) }
        }

        require(!RESULT.matches(reference)) { "`$reference` is the text of a result; a file is named like `$reference/1`" }

        throw IllegalArgumentException(
            "`${reference.take(REFERENCE_PREVIEW_CHARS)}` is not a file: name one as `#N/1` (a file a call made, " +
                    "`#0/1` for the request's own) or as `sandbox:<path>`",
        )
    }

    // the sandbox a `sandbox:<path>` reference reads from and the path it names, checked once for a file and for text
    private fun sandboxReference(reference: String): Pair<ShelfSandbox, String> {
        val path = reference.removePrefix(SANDBOX_REFERENCE_PREFIX).trim()
        val sandbox = requireNotNull(sandbox) { "there is no sandbox in this chat to read `$path` from" }
        require(path.isNotEmpty()) { "`$reference` names no path" }

        return sandbox to path
    }

    /** One tool call of the turn: what it answered and the files it made, under its number. */
    inner class Call internal constructor(val number: Int) : CallShelf {

        private val answered = CompletableDeferred<String>()
        private val made = mutableListOf<AttachedFile>()
        private var tool = ""
        private var copiesText = false

        internal val finished: Boolean
            get() = answered.isCompleted

        // set under the shelf's copying lock only
        internal var copied = false
            private set

        /**
         * Records what [toolName] answered; every call is closed, failed or not, since later calls may wait on
         * it. [copiesText] puts the answer itself in the turn's directory in the sandbox along with the files.
         */
        fun close(output: String, toolName: String = "", copiesText: Boolean = false) {
            tool = toolName
            this.copiesText = copiesText
            answered.complete(output)
        }

        /**
         * [output] as the model reads it: under the call's label, with the files it made listed after it, and
         * — when the turn's budget [cut] it short — where the whole of it still is. [resultLabelOrNull] reads
         * the label back off such a text.
         */
        fun labeled(output: String, cut: Boolean = false): String =
            buildString {
                append("[#$number] ").append(output)

                if (cut) append("\n[`#$number` still holds the whole result for any argument that takes a label]")

                synchronized(made) { made.toList() }.forEachIndexed { index, file ->
                    append("\n[#$number/${index + 1}] ").append(file.name).append(" — ").append(file.describe())
                }
            }

        override suspend fun textOrNull(value: String): String? = resolveText(value, number)

        override suspend fun file(value: String): AttachedFile = resolveFile(value, number)

        internal suspend fun text(): String = answered.await()

        internal suspend fun files(): List<AttachedFile> {
            answered.await()

            return synchronized(made) { made.toList() }
        }

        internal suspend fun copyInto(sandbox: ShelfSandbox): List<String> {
            copied = true

            val prefix = "$directory/" + number.toString().padStart(2, '0')
            val files = synchronized(made) { made.toList() }

            return buildList {
                if (copiesText) add(copy(sandbox, "$prefix-${tool.sanitizeFilename().ifBlank { "result" }}.txt") { text().encodeToByteArray() })

                files.forEachIndexed { index, file ->
                    add(copy(sandbox, "$prefix-${index + 1}-${file.name.sanitizeFilename().ifBlank { "file" }}", file.loadBytes))
                }
            }
        }

        override suspend fun keep(name: String, bytes: ByteArray): String? {
            if (!reserve(name, bytes.size)) return null

            return synchronized(made) {
                made += keptFile(name, bytes.size.toLong()) { bytes }
                "#$number/${made.size}"
            }
        }
    }

    private companion object {
        val log = KotlinLogging.logger {}

        const val TURNS_DIRECTORY = "turns"
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMdd-HHmmss")

        // the current turn and the two before it
        const val KEPT_TURNS = 3

        // what Telegram serves a bot at most, so an attachment never came in larger
        const val MAX_ATTACHMENT_MB = 20
        const val MAX_ATTACHMENT_BYTES = MAX_ATTACHMENT_MB * 1024 * 1024

        val RESULT = Regex("""#(\d+)""")
        val FILE = Regex("""#(\d+)/(\d+)""")
        const val REFERENCE_PREVIEW_CHARS = 80

        // what one turn may hold besides what the outbox already does: downloads are the large ones
        const val MAX_KEPT_BYTES = 200L * 1024 * 1024
        const val MAX_FILE_BYTES = 50 * 1024 * 1024
        const val MAX_TEXT_BYTES = 1024 * 1024
    }
}

/**
 * What the shelf needs of the person's sandbox: to read a file a reference names, and to keep the turn's
 * own directory there.
 */
interface ShelfSandbox {

    /** The file at [path], whole; one larger than [maxBytes] fails with [FileTooLarge] before any of it moves. */
    suspend fun read(path: String, maxBytes: Int): ByteArray

    suspend fun write(path: String, bytes: ByteArray)

    /** The names of the directories directly in [path]; none when it does not exist. */
    suspend fun directories(path: String): List<String>

    suspend fun deleteDirectory(path: String)

    /** What a read answers for a file over its cap: the decoder hands the message to the model as it is. */
    class FileTooLarge(path: String, maxBytes: Int) :
        IllegalArgumentException("`$path` is larger than the ${maxBytes / (1024 * 1024)} MB a file may hold here")
}

private suspend fun copy(sandbox: ShelfSandbox, path: String, bytes: suspend () -> ByteArray): String =
    runCatching {
        sandbox.write(path, bytes())
        "`$path`"
    }.getOrElse {
        it.rethrowIfCancellation()
        "`$path` could not be copied: ${it.message}"
    }

// the label `labeled` opens every result with, read back where a folded result keeps only its label
internal fun String.resultLabelOrNull(): String? = RESULT_LABEL.find(this)?.groupValues?.get(1)

private val RESULT_LABEL = Regex("""^\[(#\d+)] """)

private fun keptFile(name: String, sizeBytes: Long?, load: suspend () -> ByteArray): AttachedFile {
    val mimeType = mimeTypeOfName(name)

    return AttachedFile(
        name = name,
        fileSizeBytes = sizeBytes,
        mimeType = mimeType,
        kind = attachedFileKindOf(name, mimeType),
        isAnimation = isAnimationFile(name, mimeType),
        loadBytes = load,
    )
}

private fun AttachedFile.describe(): String {
    val kind =
        when {
            isAnimation -> "animation"
            kind == AttachedFileKind.IMAGE -> "image"
            kind == AttachedFileKind.VIDEO -> "video"
            mimeType?.startsWith("audio/") == true -> "audio"
            else -> "file"
        }

    return fileSizeBytes?.let { "$kind, ${it.readableSize()}" } ?: kind
}

private fun Long.readableSize(): String =
    when {
        this < 1024 -> "$this B"
        this < 1024 * 1024 -> "${this / 1024} KB"
        else -> String.format(Locale.ROOT, "%.1f MB", this / (1024.0 * 1024.0))
    }
