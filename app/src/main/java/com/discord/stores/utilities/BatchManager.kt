package com.discord.stores.utilities

import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * Owns every [Batched] queue of a connection and the scope their debounce timers run on.
 *
 * High frequency gateway payloads (`VOICE_STATE_UPDATE` above all) are batched so a burst
 * results in one store dispatch instead of hundreds. Payloads of any other type must first
 * [flushBatches] so events are still applied in the order they arrived on the wire.
 */
class BatchManager(
    dispatcher: CoroutineDispatcher,
    private val clock: Clock = ClockFactory.get(),
) {
    private val scope = CoroutineScope(dispatcher)
    private val batches = LinkedHashMap<String, Batched<*>>()

    fun <T> createBatched(
        type: String,
        debounceDelayMs: Long = Batched.DEFAULT_DEBOUNCE_DELAY_MS,
        maxDebounceDelayMs: Long = Batched.DEFAULT_MAX_DEBOUNCE_DELAY_MS,
    ): Batched<T> = Batched<T>(type, debounceDelayMs, maxDebounceDelayMs, scope, clock)
        .also { batches[type] = it }

    /** Flushes every batch except the one owning [incomingType], preserving wire ordering. */
    fun flushBatches(incomingType: String?) {
        batches.forEach { (type, batched) -> if (type != incomingType) batched.flush() }
    }

    fun dispose() = scope.cancel()
}
