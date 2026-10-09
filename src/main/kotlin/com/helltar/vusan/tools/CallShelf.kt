package com.helltar.vusan.tools

import com.helltar.vusan.request.AttachedFile
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext

/**
 * The turn's shelf as one tool call sees it: what came before the call, which an argument may point at
 * instead of carrying its value — an earlier result, a file a tool made, an attachment, a file in the
 * sandbox — and the place where the files the call makes are kept for the calls after it.
 *
 * The loop puts one in the coroutine context of every call. The argument decoder resolves against it
 * before handing a value to the tool, and a tool keeps what it made through [keepOnShelf], so no tool
 * carries the shelf around and every tool takes and gives the same way.
 */
interface CallShelf : CoroutineContext.Element {

    override val key: CoroutineContext.Key<*>
        get() = Key

    /**
     * The text [value] points at, or `null` when it is not a reference and is meant as written. A
     * reference that points at nothing, or at something with no text, throws [IllegalArgumentException],
     * which the call is answered with.
     */
    suspend fun textOrNull(value: String): String?

    /** The file [value] points at; anything that is not a file reference throws [IllegalArgumentException]. */
    suspend fun file(value: String): AttachedFile

    /** Keeps a file this call made, and returns the label later calls take it by; `null` when the turn holds no more. */
    suspend fun keep(name: String, bytes: ByteArray): String?

    companion object Key : CoroutineContext.Key<CallShelf>
}

/**
 * Keeps a file the running tool made on the turn's shelf, whether or not it is also sent, so a later call
 * can take it; the label it is kept under, or `null` outside a turn and once the turn holds no more.
 */
suspend fun keepOnShelf(name: String, bytes: ByteArray): String? =
    currentCoroutineContext()[CallShelf]?.keep(name, bytes)

/** What a tool answers about [what] it made and kept for a later call instead of sending, kept as [label]. */
fun keptNotSent(what: String, label: String?): String =
    if (label == null)
        "$what could not be kept: this turn holds no more files. Call again with `send` left on to deliver it instead."
    else
        "$what kept as `$label` and not sent: a later call takes it by that label."

/** A reference into the person's sandbox, `sandbox:<path>`, as the model writes it wherever a file is taken. */
const val SANDBOX_REFERENCE_PREFIX = "sandbox:"

/**
 * What a tool answers when it was to send [what] into a chat that refuses the kind. Nothing is produced,
 * since producing it would be paid for and then dropped at the send; the way to still have it is said.
 */
fun refusedByChat(what: String): String =
    "This chat does not accept $what, so nothing was produced for it. " +
            "Call again with `send` set to `false` to keep it for a later call instead, or answer another way."
