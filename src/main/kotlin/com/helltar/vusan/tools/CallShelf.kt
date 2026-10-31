package com.helltar.vusan.tools

import com.helltar.vusan.request.AttachedFile
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext

/**
 * The turn's shelf as one tool call sees it: what came before the call, which an argument may point at
 * instead of carrying its value (an earlier result, a file a tool made, an attachment, a sandbox file), and
 * where the call's files are kept for later calls.
 *
 * The loop puts one in every call's coroutine context. The argument decoder resolves against it, and a tool
 * keeps what it made through [keepOnShelf], so no tool carries the shelf around.
 */
interface CallShelf : CoroutineContext.Element {

    override val key: CoroutineContext.Key<*>
        get() = Key

    /**
     * The text [value] points at, or `null` when it is not a reference. A reference to nothing, or to
     * something without text, throws [IllegalArgumentException], which the call is answered with.
     */
    suspend fun textOrNull(value: String): String?

    /** The file [value] points at; anything but a file reference throws [IllegalArgumentException]. */
    suspend fun file(value: String): AttachedFile

    /** Keeps a file this call made and returns its label for later calls; `null` when the turn is full. */
    suspend fun keep(name: String, bytes: ByteArray): String?

    companion object Key : CoroutineContext.Key<CallShelf>
}

/**
 * Keeps a file the running tool made on the turn's shelf, sent or not, for later calls; returns its label, or
 * `null` outside a turn and once the turn is full.
 */
suspend fun keepOnShelf(name: String, bytes: ByteArray): String? =
    currentCoroutineContext()[CallShelf]?.keep(name, bytes)

/** What a tool answers about [what] it made and kept as [label] instead of sending. */
fun keptNotSent(what: String, label: String?): String =
    if (label == null)
        "$what could not be kept: this turn holds no more files. Call again with `send` left on to deliver it instead."
    else
        "$what kept as `$label` and not sent: a later call takes it by that label."

/** A reference into the person's sandbox, `sandbox:<path>`, wherever a file is taken. */
const val SANDBOX_REFERENCE_PREFIX = "sandbox:"

/**
 * What a tool answers when asked to send [what] into a chat refusing that kind. Nothing is produced, since it
 * would be paid for and dropped at the send; the way to still get it is said.
 */
fun refusedByChat(what: String): String =
    "This chat does not accept $what, so nothing was produced for it. " +
            "Call again with `send` set to `false` to keep it for a later call instead, or answer another way."
