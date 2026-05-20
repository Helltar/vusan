package com.helltar.vusan.common

import kotlinx.coroutines.CancellationException

internal fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
