package com.helltar.vusan.telegram

import com.helltar.vusan.common.asFileSize
import com.helltar.vusan.common.collapseWhitespaceAndCap
import com.helltar.vusan.common.escapeHtml
import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.i18n.Messages
import com.helltar.vusan.tools.sandbox.CommandStatus
import com.helltar.vusan.tools.sandbox.PublishedSite
import com.helltar.vusan.tools.sandbox.SandboxClient
import com.helltar.vusan.tools.sandbox.SandboxSnapshot
import com.helltar.vusan.tools.sandbox.SandboxStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.withTimeoutOrNull

private val log = KotlinLogging.logger {}

// an ephemeral command is answered within 15 seconds or not at all, so a slow server is given up on while
// the apology can still arrive
private val SERVER_WAIT = 8.seconds

// a note is the model's own sentence about a snapshot, shown here as one line of a list
private const val MAX_NOTE_CHARS = 80

/**
 * What `/sandbox` shows one person: their sandbox as the server reports it, their site when one is up, and
 * how many of their commands still run.
 */
internal data class SandboxOverview(
    val status: SandboxStatus,
    val site: PublishedSite?,
    val commandsRunning: Int,
)

/**
 * The whole `/sandbox` answer for the telegram user [userId]: what they have, that they have nothing yet, or
 * that the server could not be asked. Only asks: a person who never ran a command is left without a sandbox.
 */
internal suspend fun SandboxClient.overviewReply(userId: Long, messages: Messages, now: Instant = Clock.System.now()): String {
    val person = sandboxOf(telegramUser(userId).key)

    val overview =
        withTimeoutOrNull(SERVER_WAIT) {
            runCatching {
                person.status()?.let { status ->
                    SandboxOverview(
                        status = status,
                        // a server without a pages role refuses the question, which is no reason to hide the rest
                        site = runCatching { person.publishedSite() }.onFailure { it.rethrowIfCancellation() }.getOrNull(),
                        commandsRunning = person.listCommands().count { it.status == CommandStatus.RUNNING },
                    )
                }
            }.onFailure { it.rethrowIfCancellation() }
        }

    if (overview == null || overview.isFailure) {
        log.warn { "no sandbox overview for user=$userId: ${overview?.exceptionOrNull()?.message ?: "the server did not answer in time"}" }
        return messages.sandboxUnavailableReply
    }

    return overview.getOrNull()?.render(messages, now) ?: messages.sandboxNoneReply
}

/**
 * The answer as HTML: the title, then one fact a line. Every date and number in it is the server's: nothing here knows how
 * long a home or a site is kept, only when this one goes.
 */
internal fun SandboxOverview.render(messages: Messages, now: Instant): String =
    buildString {
        appendLine("<b>${messages.sandboxTitle}</b>")
        appendLine()
        appendLine(messages.sandboxState(status.running, commandsRunning))

        // retention took the files while a site kept the sandbox, so there is no date left to count down to
        appendLine(
            status.homeReleasedAt
                ?.let { messages.sandboxFilesReleased(it.asDate()) }
                ?: messages.sandboxFilesKept(status.deleteAfter.asDate(), status.deleteAfter.daysFrom(now)),
        )

        appendLine(messages.sandboxSnapshots(status.snapshots.size, status.maxSnapshots))
        status.snapshots.forEach { appendLine(it.line()) }

        site?.let {
            appendLine(messages.sandboxSite(it.url.escapeHtml(), it.files, it.bytes.asFileSize(), it.until.asDate(), it.until.daysFrom(now)))
        }
    }.trim()

private fun SandboxSnapshot.line(): String {
    val note = note?.collapseWhitespaceAndCap(MAX_NOTE_CHARS)?.let { " · ${it.escapeHtml()}" }.orEmpty()

    return "  • ${takenAt.asDate()} · ${bytes.asFileSize()}$note"
}

// a person reads a term as a day; the hour is the server's business
private fun Instant.asDate(): String = toString().substringBefore('T')

private fun Instant.daysFrom(now: Instant): Long = (this - now).inWholeDays.coerceAtLeast(0)
