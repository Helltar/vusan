package com.helltar.vusan.tools.sandbox

/*
 * whole documents as a regolith server sends them: the sdk fails on any missing required field, so a
 * fake answering with a fragment tests nothing.
 */

/** The id a fake server gives every sandbox; the alias is what the bot sends. */
internal const val SANDBOX_ID = "5f2b9c7d1e3a4f6b8c0d2e4f6a8b0c1d"

internal fun sandboxInfo(
    alias: String,
    id: String = SANDBOX_ID,
    homeReleasedAt: String? = null,
    state: String = "stopped",
    lastSessionEnd: String? = null,
): String =
    """{"id":"$id","alias":"$alias","image":"ghcr.io/example/sandbox:1.0","imagePolicy":{"mode":"default"},""" +
        """"resources":{"cpus":1.0,"memoryMb":1024,"homeMb":4096},"network":{"mode":"public","allow":[]},""" +
        """"lifecycle":{"idleStopSeconds":900,"maxSessionSeconds":86400,"retainDays":30},"env":{},"labels":{},""" +
        """"state":"$state",""" +
        (lastSessionEnd?.let { """"lastSessionEnd":{"reason":"$it","at":"2026-09-13T11:40:00Z"},""" } ?: "") +
        """"createdAt":"2026-09-13T12:00:00Z","lastUsedAt":"2026-09-13T12:00:00Z",""" +
        """"deleteAfter":"2026-10-13T12:00:00Z"""" + (homeReleasedAt?.let { ""","homeReleasedAt":"$it"""" } ?: "") + "}"

/** The answer to a lookup by alias: the sandbox filed under it, or an empty page when there is none. */
internal fun sandboxPage(vararg sandboxes: String): String = sandboxes.joinToString(",", prefix = """{"sandboxes":[""", postfix = "]}")

internal fun snapshotInfo(id: String, createdAt: String = "2026-09-13T12:10:00Z", note: String? = null): String =
    """{"id":"$id","createdAt":"$createdAt","bytes":52428800,"homeMb":4096""" + (note?.let { ""","note":"$it"""" } ?: "") + "}"

/** `GET /v1/info`, the limits a client reads instead of keeping a copy. */
internal fun serverInfo(protocol: Int = 5, publishing: Boolean = true, maxSnapshots: Int = 5): String =
    """{"version":"0.8.1","protocol":$protocol,"defaults":{"image":"ghcr.io/example/sandbox:1.0",""" +
        """"resources":{"cpus":1.0,"memoryMb":1024,"homeMb":4096},"network":{"mode":"public","allow":[]},""" +
        """"lifecycle":{"idleStopSeconds":900,"maxSessionSeconds":86400,"retainDays":30},"execTimeoutSeconds":120,"siteDays":30},""" +
        """"limits":{"images":["ghcr.io/example/sandbox:1.0"],"maxCpus":2.0,"maxMemoryMb":4096,"maxHomeMb":16384,""" +
        """"maxSessionSeconds":86400,"maxRetainDays":90,"maxSiteDays":365,"maxExecTimeoutSeconds":3600,""" +
        """"maxFileBytes":67108864,"maxOutputBytes":8388608,"maxLabels":32,"unattendedCpuSeconds":600,"maxSnapshots":$maxSnapshots},""" +
        """"publishing":$publishing}"""

internal fun fileEntry(name: String, size: Long, type: String = "file"): String =
    """{"path":"/home/sandbox/$name","name":"$name","type":"$type","size":$size,""" +
        """"modifiedAt":"2026-09-13T12:00:00Z","mode":420}"""

internal fun problemDocument(code: String, status: Int, title: String, detail: String): String =
    """{"type":"urn:regolith:error:$code","title":"$title","status":$status,"detail":"$detail","code":"$code"}"""

/** One page of output as the server sends it, with its exec. */
internal fun outputPage(frames: String, nextOffset: Long, complete: Boolean, exec: String): String =
    """{"frames":$frames,"nextOffset":$nextOffset,"complete":$complete,"exec":$exec}"""
