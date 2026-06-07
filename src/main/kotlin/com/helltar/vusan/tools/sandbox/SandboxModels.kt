package com.helltar.vusan.tools.sandbox

import kotlinx.serialization.Serializable

@Serializable
data class RunRequest(val code: String, val files: List<SandboxFile> = emptyList())

@Serializable
data class RunResponse(
    val ok: Boolean,
    val timedOut: Boolean = false,
    val error: String = "",
    val stdout: String = "",
    val stderr: String = "",
    val files: List<SandboxFile> = emptyList(),
    val skipped: List<SkippedFile> = emptyList(),
    val elapsedMs: Long = 0
)

@Serializable
data class SandboxFile(
    val name: String,
    val base64: String
)

@Serializable
data class SkippedFile(
    val name: String,
    val bytes: Long,
    val reason: String
)
