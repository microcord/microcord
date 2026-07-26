package com.discord.stores.utilities

import android.util.Log
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.SerializedSubject

/**
 * Coalesces a high frequency stream of single values into batches.
 *
 * Values are queued and emitted together once [debounceDelayMs] has passed without another
 * value, or immediately once the queue has been open for [maxDebounceDelayMs] so a sustained
 * burst still makes progress.
 */
class Batched<T>(
    val type: String,
    private val debounceDelayMs: Long = DEFAULT_DEBOUNCE_DELAY_MS,
    private val maxDebounceDelayMs: Long = DEFAULT_MAX_DEBOUNCE_DELAY_MS,
    private val scope: CoroutineScope,
    private val clock: Clock = ClockFactory.get(),
) {
    private val subject = SerializedSubject<List<T>, List<T>>(BehaviorSubject.create())
    private var queue = ArrayList<T>()
    private var queueStartTime = QUEUE_NOT_STARTED
    private var debounceJob: Job? = null

    fun observe(): Observable<List<T>> = subject

    /** Emits everything queued so far, even when the debounce window has not elapsed. */
    @Synchronized
    fun flush() {
        debounceJob?.cancel()
        debounceJob = null
        queueStartTime = QUEUE_NOT_STARTED
        val batch = queue
        queue = ArrayList()
        subject.onNext(batch)
    }

    @Synchronized
    fun onNext(value: T) {
        if (value == null) Log.i(TAG, "onNext received a NULL value for Batched[$type]")
        queue.add(value)
        val now = clock.currentTimeMillis()
        if (queueStartTime == QUEUE_NOT_STARTED) queueStartTime = now
        if (now - queueStartTime >= maxDebounceDelayMs) {
            flush()
        } else {
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(debounceDelayMs)
                flush()
            }
        }
    }

    /**
     * Entry point for dispatch tables that only know the payload as [Any]; the caller is
     * responsible for routing a value to the batch declared for its type.
     */
    @Suppress("UNCHECKED_CAST")
    fun onNextAny(value: Any) = onNext(value as T)

    companion object {
        const val DEFAULT_DEBOUNCE_DELAY_MS = 100L
        const val DEFAULT_MAX_DEBOUNCE_DELAY_MS = 300L
        private const val QUEUE_NOT_STARTED = -1L
        private const val TAG = "Batched"
    }
}
