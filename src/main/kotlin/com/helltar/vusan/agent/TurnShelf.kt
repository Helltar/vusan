package com.helltar.vusan.agent

import com.helltar.vusan.common.asFileSize
import com.helltar.vusan.request.PlatformLimits
import com.helltar.vusan.common.mimeTypeOfName
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.common.sanitizeFilename
import com.helltar.vusan.request.AttachedFile
import com.helltar.vusan.request.AttachedFileKind
import com.helltar.vusan.request.attachedFileKindOf
import com.helltar.vusan.request.isAnimationFile
import com.helltar.vusan.tools.CallShelf
import com.helltar.vusan.tools.SANDBOX_REFERENCE_PREFIX
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Everything one turn's tools produced, under the labels the model reads, so one call can take what another
 * made: `#3` is the third call's text, `#5/1` the fifth call's first file, `#0/1` the request's own
 * attachment, `sandbox:<path>` a file in the person's sandbox.
 *
 * The loop opens a [Call] per tool call in batch order and runs the tool inside it; the call is the
 * [CallShelf] the argument decoder resolves against. A call may only take what came before it, so parallel
 * calls never wait in a circle.
 *
 * One turn, in memory only. Stored history keeps results without labels, so the next turn has nothing stale
 * to point at; a turn continuing the last request numbers on from where it stopped ([numberFrom]), so an
 * earlier label is refused rather than mistaken for this turn's. What reaches the sandbox stays a little
 * longer, under `turns/`.
 */
class TurnShelf(
    private val attachments: List<AttachedFile> = emptyList(),
    startedAt: Instant = Instant.now(),
) {

    private val calls = mutableListOf<Call>()
    private var keptBytes = 0L

    private var firstCall = 1

    // named by the turn's start so directories sort in order; settled at the first copy, when the sandbox
    // says whether the name is free
    private var directory = "$TURNS_DIRECTORY/" + STAMP.format(startedAt.atZone(ZoneId.systemDefault()))

    // copies run from whichever sandbox call comes next, and two in a batch must not write the same thing
    private val copying = Mutex()
    private var started = false

    // the person's sandbox, when this chat has one; set by whoever opens it for the turn
    @Volatile
    private var sandbox: ShelfSandbox? = null

    val nextNumber: Int
        get() = synchronized(calls) { firstCall + calls.size }

    /** Lets `sandbox:` references read from the turn's sandbox and the turn's files reach it. */
    fun connectSandbox(sandbox: ShelfSandbox) {
        this.sandbox = sandbox
    }

    /** Numbers the calls from [first] on, where the continued request left its labels. */
    fun numberFrom(first: Int) {
        require(first > 0) { "calls are numbered from one" }

        synchronized(calls) {
            check(calls.isEmpty()) { "the numbering is settled before the first call" }
            firstCall = first
        }
    }

    /** Opens the next call; the loop runs the tool inside it and closes it with the answer. */
    fun open(): Call =
        synchronized(calls) { Call(firstCall + calls.size).also { calls += it } }

    /**
     * Writes what has not reached `turns/<when>/` in the sandbox yet: the attachments as `00-1-name` on the
     * first copy, files of finished calls as `05-1-name`, and material answers as `03-webSearch.txt`. The
     * first copy also prunes all but the last few turns. One line per thing written or skipped; nothing
     * without a sandbox.
     */
    suspend fun copyToSandbox(): List<String> {
        val sandbox = sandbox ?: return emptyList()

        return copying.withLock {
            val lines = mutableListOf<String>()

            if (!started) {
                started = true
                directory = claimDirectory(sandbox)
                attachments.forEachIndexed { index, file -> lines += copyAttachment(sandbox, index + 1, file) }
            }

            synchronized(calls) { calls.filter { it.finished && !it.copied } }.forEach { lines += it.copyInto(sandbox) }

            lines
        }
    }

    // a turn often picks up what the previous one got or made, so a few stay; the rest would fill a
    // fixed-size home. the same listing says whether the name is taken: one person starting two turns within
    // a second in two chats gets the next free name
    private suspend fun claimDirectory(sandbox: ShelfSandbox): String {
        val existing =
            runCatching { sandbox.directories(TURNS_DIRECTORY) }
                .getOrElse {
                    it.rethrowIfCancellation()
                    log.warn { "turn directories could not be listed, old ones left in place: ${it.message}" }
                    return directory
                }

        runCatching {
            existing.sorted().dropLast(KEPT_TURNS - 1).forEach { sandbox.deleteDirectory("$TURNS_DIRECTORY/$it") }
        }.onFailure {
            it.rethrowIfCancellation()
            log.warn { "old turn directories left in place: ${it.message}" }
        }

        val stamp = directory.substringAfterLast('/')
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) stamp else "$stamp-$it" }.first { it !in existing }

        return "$TURNS_DIRECTORY/$name"
    }

    private suspend fun copyAttachment(sandbox: ShelfSandbox, index: Int, file: AttachedFile): String {
        val path = "$directory/00-$index-${file.name.sanitizeFilename().ifBlank { "attachment" }}"

        if ((file.fileSizeBytes ?: 0) > MAX_ATTACHMENT_BYTES) return "`${file.name}` is over the $MAX_ATTACHMENT_MB MB an attachment may bring in"

        return copy(sandbox, path) {
            file.loadBytes().also { require(it.size <= MAX_ATTACHMENT_BYTES) { "over the $MAX_ATTACHMENT_MB MB an attachment may bring in" } }
        }
    }

    // the turn's whole holding across calls
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
        require(number in firstCall until from) {
            when {
                number >= from -> "`#$number` is not an earlier result of this turn: a call can only take what came before it"
                number > 0 -> "`#$number` is a result of an earlier turn, which is not kept: call its tool again"
                else -> "`#0` holds the request's own files; name one as `#0/1`"
            }
        }

        return synchronized(calls) { calls[number - firstCall] }
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

        // the attachment block shows a file by name, and a model copying the name back means that file
        val named = attachments.filter { it.name == reference }

        if (named.size == 1) return named.single()

        require(named.isEmpty()) {
            "`$reference` is the name of ${named.size} attached files; name one as `#0/1` to `#0/${attachments.size}`"
        }

        throw IllegalArgumentException(
            "`${reference.take(REFERENCE_PREVIEW_CHARS)}` is not a file: name one as `#N/1` (a file a call made, " +
                    "`#0/1` for the request's own) or as `sandbox:<path>`",
        )
    }

    // the sandbox and path a `sandbox:<path>` reference names, checked once for file and text use
    private fun sandboxReference(reference: String): Pair<ShelfSandbox, String> {
        val path = reference.removePrefix(SANDBOX_REFERENCE_PREFIX).trim()
        val sandbox = requireNotNull(sandbox) { "there is no sandbox in this chat to read `$path` from" }
        require(path.isNotEmpty()) { "`$reference` names no path" }

        return sandbox to path
    }

    /** One tool call of the turn: its answer and files, under its number. */
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
         * Records what [toolName] answered. Every call is closed, failed or not, since later calls may wait
         * on it. [copiesText] also puts the answer in the turn's sandbox directory.
         */
        fun close(output: String, toolName: String = "", copiesText: Boolean = false) {
            tool = toolName
            this.copiesText = copiesText
            answered.complete(output)
        }

        /**
         * [output] as the model reads it: under the call's label, with its files listed after, and where the
         * whole of it is when the budget [cut] it. [resultLabelOrNull] reads the label back.
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

        // an attachment is never larger than the platform serves
        const val MAX_ATTACHMENT_MB = PlatformLimits.DOWNLOAD_MB
        const val MAX_ATTACHMENT_BYTES = MAX_ATTACHMENT_MB * 1024 * 1024

        val RESULT = Regex("""#(\d+)""")
        val FILE = Regex("""#(\d+)/(\d+)""")
        const val REFERENCE_PREVIEW_CHARS = 80

        // what one turn may hold besides the outbox; downloads are the large ones
        const val MAX_KEPT_BYTES = 200L * 1024 * 1024
        const val MAX_FILE_BYTES = 50 * 1024 * 1024
        const val MAX_TEXT_BYTES = 1024 * 1024
    }
}

/**
 * What the shelf needs of the person's sandbox: reading a referenced file and keeping the turn's directory.
 */
interface ShelfSandbox {

    /** The whole file at [path]; one over [maxBytes] fails with [FileTooLarge] before any of it moves. */
    suspend fun read(path: String, maxBytes: Int): ByteArray

    suspend fun write(path: String, bytes: ByteArray)

    /** Directory names directly in [path]; none when it does not exist. */
    suspend fun directories(path: String): List<String>

    suspend fun deleteDirectory(path: String)

    /** The over-cap read failure; the decoder hands its message to the model as is. */
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

// reads back the label `labeled` opens every result with, where a folded result keeps only that
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

    return fileSizeBytes?.let { "$kind, ${it.asFileSize()}" } ?: kind
}
