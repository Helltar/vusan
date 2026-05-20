package com.helltar.vusan.common

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CancellationTest {

    @Test
    fun `rethrowIfCancellation rethrows cancellation exceptions`() {
        assertFailsWith<CancellationException> {
            CancellationException("stop").rethrowIfCancellation()
        }
    }

    @Test
    fun `rethrowIfCancellation ignores non cancellation throwables`() {
        IllegalStateException("boom").rethrowIfCancellation()
    }
}
