package com.helltar.vusan.config

import kotlin.io.path.Path
import kotlin.io.path.readText

internal fun readWorkspaceToken(token: String?, file: String?): String {
    val resolved = token?.trim()?.takeIf { it.isNotEmpty() }
        ?: file?.let { Path(it).readText().trim() }
    requireNotNull(resolved) { "WORKSPACE_TOKEN or WORKSPACE_TOKEN_FILE is required when WORKSPACE_URL is set" }
    require(resolved.length in 32..256 && resolved.all { it.code in 0x21..0x7e }) {
        "Workspace token must contain 32 to 256 printable non-whitespace ASCII characters"
    }
    return resolved
}
