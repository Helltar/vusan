package com.helltar.vusan.tools.sandbox

import com.helltar.vusan.common.asFileSize
import com.helltar.vusan.tools.Arg
import com.helltar.vusan.tools.Tool
import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.tools.requireToolText
import com.helltar.vusan.tools.suspendToolGuard
import java.util.Locale
import kotlin.time.Instant

// a server-made id is 32 characters; this only keeps model text short before it is echoed back
private const val MAX_SNAPSHOT_ID_CHARS = 64

// the server holds a note to its own length and says so; this only keeps a pasted essay off the wire
private const val MAX_NOTE_CHARS = 1_000

/**
 * What a person does to their sandbox as a whole rather than to its files: snapshots of the home to go back
 * to, how the server keeps it, and a restart. Rarely needed in a turn, so the set sits behind a group.
 */
class SandboxManagementTools(
    // the turn's own handle, shared with the shell tools
    private val sandbox: SandboxClient.PersonSandbox,
) : ToolSet {

    @Tool(SandboxManagementToolDescriptions.SANDBOX_STATUS, readOnly = true)
    suspend fun sandboxStatus(): String = suspendToolGuard {
        val status = sandbox.status() ?: return@suspendToolGuard "This person has no sandbox yet; the first command creates an empty one."
        describeStatus(status)
    }

    @Tool(SandboxManagementToolDescriptions.TAKE_SNAPSHOT)
    suspend fun takeSandboxSnapshot(
        @Arg(SandboxManagementToolDescriptions.NOTE)
        note: String,
    ): String = suspendToolGuard {
        // a note is one line on the server, so a wrapped one is joined rather than refused
        val line = note.requireToolText("Note", MAX_NOTE_CHARS).split(Regex("\\s+")).joinToString(" ")
        val running = sandbox.listCommands().filter { it.status == CommandStatus.RUNNING }.map { it.jobId }
        require(running.isEmpty()) {
            "Still running: ${running.joinToString(", ")}. Taking a snapshot would end it; wait for it with `readSandboxCommand` or cancel it first."
        }
        val snapshot = sandbox.takeSnapshot(line)
        "Snapshot ${snapshot.id} taken (${snapshot.bytes.asFileSize()}), noted \"${snapshot.note.orEmpty()}\". " +
            "The sandbox was stopped to copy it, so any background process is gone; the next command starts it again."
    }

    @Tool(SandboxManagementToolDescriptions.RESTORE_SNAPSHOT)
    suspend fun restoreSandboxSnapshot(
        @Arg(SandboxManagementToolDescriptions.RESTORE_SNAPSHOT_ID)
        snapshotId: String,
    ): String = suspendToolGuard {
        val id = snapshotId.requireToolText("Snapshot id", MAX_SNAPSHOT_ID_CHARS)
        val restored = requireNotNull(sandbox.restoreSnapshot(id)) { missing(id) }
        "The home is back as snapshot ${restored.describe()} kept it; everything written since then is gone. " +
            "The snapshot is still there to restore again, and the next command starts the sandbox."
    }

    @Tool(SandboxManagementToolDescriptions.DELETE_SNAPSHOT)
    suspend fun deleteSandboxSnapshot(
        @Arg(SandboxManagementToolDescriptions.DELETE_SNAPSHOT_ID)
        snapshotId: String,
    ): String = suspendToolGuard {
        val id = snapshotId.requireToolText("Snapshot id", MAX_SNAPSHOT_ID_CHARS)
        val deleted = requireNotNull(sandbox.deleteSnapshot(id)) { missing(id) }
        "Deleted snapshot ${deleted.describe()}. The home and the other snapshots are unchanged."
    }

    @Tool(SandboxManagementToolDescriptions.RESTART_SANDBOX)
    suspend fun restartSandbox(): String = suspendToolGuard {
        if (sandbox.restart()) {
            "The sandbox was restarted: every process in it ended and every file stayed. The next command starts a fresh session."
        } else {
            "Nothing was running in the sandbox, so there was nothing to restart; the next command starts a fresh session."
        }
    }
}

private fun missing(id: String): String = "This sandbox has no snapshot `$id`; `sandboxStatus` lists the ones it keeps"

private fun describeStatus(status: SandboxStatus): String = buildString {
    appendLine(
        "The sandbox is ${if (status.running) "running" else "stopped"}. " +
            "It has ${status.memoryMb} MB of memory, ${status.cpus.asCount()} CPU(s) and a ${status.homeMb} MB disk.",
    )
    status.lastSessionEnd?.let { appendLine("Its last session ended: $it") }
    appendLine(
        "Last used ${status.lastUsedAt.asDate()}. If it goes unused, its files are deleted on ${status.deleteAfter.asDate()}, " +
            "and every use moves that date; a published site keeps a date of its own.",
    )
    status.homeReleasedAt?.let {
        appendLine("Its files were deleted on ${it.asDate()} after it went unused, while its site stayed up; the next command starts an empty home.")
    }

    val limit = status.maxSnapshots?.let { " of $it" }.orEmpty()

    if (status.snapshots.isEmpty()) {
        append("No snapshots (0$limit).")
    } else {
        appendLine("Snapshots (${status.snapshots.size}$limit), oldest first:")
        status.snapshots.forEach { appendLine("- ${it.describe()}, ${it.bytes.asFileSize()}") }
    }
}.trim()

private fun SandboxSnapshot.describe(): String =
    "$id (${note?.let { "\"$it\", " }.orEmpty()}taken ${takenAt.asMinute()})"

// a person reads a retention date as a day; the hour is the server's business
private fun Instant.asDate(): String = toString().substringBefore('T')

// two snapshots of one day are told apart by the minute
private fun Instant.asMinute(): String = toString().take("yyyy-MM-ddTHH:mm".length).replace('T', ' ') + " UTC"

private fun Double.asCount(): String = if (this % 1.0 == 0.0) toLong().toString() else String.format(Locale.ROOT, "%.1f", this)
