package com.helltar.vusan.tools.sandbox

import kotlinx.serialization.Serializable

@Serializable
data class RunRequest(val code: String)

@Serializable
data class RunResponse(
    val ok: Boolean,
    val timedOut: Boolean = false,
    val error: String = "",
    val stdout: String = "",
    val stderr: String = "",
    val files: List<SandboxFile> = emptyList()
)

@Serializable
data class SandboxFile(
    val name: String,
    val base64: String
)
