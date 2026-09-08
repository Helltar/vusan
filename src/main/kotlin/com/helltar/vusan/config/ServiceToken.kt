package com.helltar.vusan.config

import kotlin.io.path.Path
import kotlin.io.path.readText

/**
 * The shared secret for one of the services the bot calls out to. Both sides are configured with the
 * same value and nothing is generated here, so a missing or weak one is a configuration error rather
 * than something to paper over: the workspace runs model-authored shell and the site host publishes to
 * the internet.
 */
internal fun readServiceToken(prefix: String, token: String?, file: String?): String {
    val resolved = token?.trim()?.takeIf { it.isNotEmpty() }
        ?: file?.let { Path(it).readText().trim() }
    requireNotNull(resolved) { "${prefix}_TOKEN or ${prefix}_TOKEN_FILE is required when ${prefix}_URL is set" }
    require(resolved.length in 32..256 && resolved.all { it.code in 0x21..0x7e }) {
        "$prefix token must contain 32 to 256 printable non-whitespace ASCII characters"
    }
    return resolved
}
