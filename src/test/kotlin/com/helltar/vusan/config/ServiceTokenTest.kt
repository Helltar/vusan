package com.helltar.vusan.config

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServiceTokenTest {
    private val secret = "synthetic-workspace-secret-123456789"

    @Test
    fun `service authentication is mandatory and rejects weak secrets`() {
        assertFailsWith<IllegalArgumentException> { readServiceToken("WORKSPACE", null, null) }
        assertFailsWith<IllegalArgumentException> { readServiceToken("WORKSPACE", "short", null) }
        assertFailsWith<IllegalArgumentException> { readServiceToken("WORKSPACE", "a".repeat(32) + " embedded space", null) }
        assertEquals(secret, readServiceToken("WORKSPACE", secret, null))
    }

    @Test
    fun `compose generated token is read from a file while an explicit token takes precedence`() {
        val path = Files.createTempFile("workspace-auth-test", ".txt")
        try {
            path.writeText("$secret\n")
            assertEquals(secret, readServiceToken("WORKSPACE", null, path.toString()))
            val override = "explicit-workspace-secret-123456789"
            assertEquals(override, readServiceToken("WORKSPACE", override, path.toString()))
            path.writeText("broken")
            assertFailsWith<IllegalArgumentException> { readServiceToken("WORKSPACE", null, path.toString()) }
        } finally {
            path.deleteIfExists()
        }
    }
}
