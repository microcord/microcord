package com.discord.utilities.error

import java.util.concurrent.CancellationException

/**
 * Cancellation that still carries the classified failure that caused it.
 *
 * Reconstruction of the reference `com.discord.utilities.error.AppCancellationException`. The
 * reference uses it to bridge Rx failures into coroutine cancellation without losing the
 * `Error` payload, so callers can inspect [error] instead of re-classifying the throwable.
 */
data class AppCancellationException(
    override val message: String?,
    override val cause: Throwable?,
    val error: RestApiError,
) : CancellationException(message)
