package com.helltar.vusan.tools.sandbox

/*
 * whole documents as a regolith server sends them. the sdk decodes every field the protocol requires
 * and fails on a missing one, so a fake that answers with a fragment tests nothing.
 */

internal fun sandboxInfo(name: String): String =
    """{"name":"$name","image":"ghcr.io/example/sandbox:1.0","imagePolicy":{"mode":"default"},""" +
        """"resources":{"cpus":1.0,"memoryMb":1024,"homeMb":4096},"network":{"mode":"public","allow":[]},""" +
        """"lifecycle":{"idleStopSeconds":900,"maxSessionSeconds":86400,"retainDays":30},"env":{},"labels":{},""" +
        """"state":"stopped","createdAt":"2026-09-13T12:00:00Z","lastUsedAt":"2026-09-13T12:00:00Z",""" +
        """"deleteAfter":"2026-10-13T12:00:00Z"}"""

internal fun fileEntry(name: String, size: Long, type: String = "file"): String =
    """{"path":"/home/sandbox/$name","name":"$name","type":"$type","size":$size,""" +
        """"modifiedAt":"2026-09-13T12:00:00Z","mode":420}"""

internal fun problemDocument(code: String, status: Int, title: String, detail: String): String =
    """{"type":"urn:regolith:error:$code","title":"$title","status":$status,"detail":"$detail","code":"$code"}"""
