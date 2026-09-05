package com.helltar.vusan.config

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkspaceTokenTest {
    private val secret = "synthetic-workspace-secret-123456789"

    @Test
    fun `workspace authentication is mandatory and rejects weak secrets`() {
        assertFailsWith<IllegalArgumentException> { readWorkspaceToken(null, null) }
        assertFailsWith<IllegalArgumentException> { readWorkspaceToken("short", null) }
        assertFailsWith<IllegalArgumentException> { readWorkspaceToken("a".repeat(32) + " embedded space", null) }
        assertEquals(secret, readWorkspaceToken(secret, null))
    }

    @Test
    fun `compose generated token is read from a file while an explicit token takes precedence`() {
        val path = Files.createTempFile("workspace-auth-test", ".txt")
        try {
            path.writeText("$secret\n")
            assertEquals(secret, readWorkspaceToken(null, path.toString()))
            val override = "explicit-workspace-secret-123456789"
            assertEquals(override, readWorkspaceToken(override, path.toString()))
            path.writeText("broken")
            assertFailsWith<IllegalArgumentException> { readWorkspaceToken(null, path.toString()) }
        } finally {
            path.deleteIfExists()
        }
    }
}
