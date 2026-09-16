package com.helltar.vusan.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServiceTokenTest {
    private val secret = "synthetic-sandbox-secret-123456789"

    @Test
    fun `service authentication is mandatory and rejects weak secrets`() {
        assertFailsWith<IllegalArgumentException> { readServiceToken("REGOLITH", null) }
        assertFailsWith<IllegalArgumentException> { readServiceToken("REGOLITH", " ") }
        assertFailsWith<IllegalArgumentException> { readServiceToken("REGOLITH", "short") }
        assertFailsWith<IllegalArgumentException> { readServiceToken("REGOLITH", "a".repeat(32) + " embedded space") }
        assertEquals(secret, readServiceToken("REGOLITH", " $secret\n"))
    }
}
