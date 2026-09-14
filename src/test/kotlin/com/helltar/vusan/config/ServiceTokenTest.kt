package com.helltar.vusan.config

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServiceTokenTest {
    private val secret = "synthetic-sandbox-secret-123456789"

    @Test
    fun `service authentication is mandatory and rejects weak secrets`() {
        assertFailsWith<IllegalArgumentException> { readServiceToken("REGOLITH", null, null) }
        assertFailsWith<IllegalArgumentException> { readServiceToken("REGOLITH", "short", null) }
        assertFailsWith<IllegalArgumentException> { readServiceToken("REGOLITH", "a".repeat(32) + " embedded space", null) }
        assertEquals(secret, readServiceToken("REGOLITH", secret, null))
    }

    @Test
    fun `compose generated token is read from a file while an explicit token takes precedence`() {
        val path = Files.createTempFile("sandbox-auth-test", ".txt")

        try {
            path.writeText("$secret\n")
            assertEquals(secret, readServiceToken("REGOLITH", null, path.toString()))
            val override = "explicit-sandbox-secret-123456789"
            assertEquals(override, readServiceToken("REGOLITH", override, path.toString()))
            path.writeText("broken")
            assertFailsWith<IllegalArgumentException> { readServiceToken("REGOLITH", null, path.toString()) }
        } finally {
            path.deleteIfExists()
        }
    }
}
