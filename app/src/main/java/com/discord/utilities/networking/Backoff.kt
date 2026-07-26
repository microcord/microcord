package com.discord.utilities.networking

import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ExecutorService
import kotlin.math.min

/**
 * Jittered, capped exponential backoff with a process-wide "pending" gate.
 *
 * Reconstructed from the reference `com.discord.utilities.networking.Backoff` (utils module). The
 * decompiled Java body of [fail] is mis-rendered by JADX (it multiplies an uninitialised local), so
 * the growth formula below was taken from the DEX instructions of
 * `Lcom/discord/utilities/networking/Backoff;->fail(Lkotlin/jvm/functions/Function0;)J`:
 *
 * ```
 * fails++
 * jitterFactor = jitter ? Math.random() : 1.0
 * current = min(current + (long)(2 * current * jitterFactor), maxBackoffMs)
 * if (callback != null && !isPending) { isPending = true; scheduler.schedule({ ... }, current) }
 * return current
 * ```
 *
 * Note the growth factor this implies: without jitter each failure triples the delay, and
 * [minBackoffMs] is the seed rather than the first delay — the first armed retry already waits
 * `3 * minBackoffMs`. With jitter on (the default) each failure multiplies by a random factor in
 * `[1, 3)`. This is unintuitive but it is what the shipped bytecode does.
 *
 * The [isPending] flag is what makes this shared rather than per-call: while a delayed retry is
 * armed, callers such as `MessageQueue.processNextRequest` refuse to start new work, and a later
 * [fail] does not arm a second timer. [cancel] disarms it (used when connectivity is restored) and
 * [succeed] additionally resets the accumulated delay.
 *
 * Every mutating entry point is `synchronized` exactly as in the reference, because the timer
 * thread and the caller thread both touch this state.
 */
class Backoff @JvmOverloads constructor(
    private val minBackoffMs: Long = DEFAULT_MIN_BACKOFF_MS,
    private val maxBackoffMs: Long = DEFAULT_MIN_BACKOFF_MS * 10,
    private val failureThreshold: Int = Int.MAX_VALUE,
    private val jitter: Boolean = true,
    private val scheduler: Scheduler = TimerScheduler(),
) {

    /** Delayed execution seam. Replaced in tests so backoff timing is deterministic. */
    interface Scheduler {
        fun schedule(action: () -> Unit, delayMs: Long)
        fun cancel()
    }

    private var current: Long = minBackoffMs
    private var fails: Int = 0

    /**
     * True while a delayed retry armed by [fail] has not run yet. Callers use this as a gate so a
     * failing endpoint is not hammered from several code paths at once.
     */
    var isPending: Boolean = false
        private set

    fun hasReachedFailureThreshold(): Boolean = fails > failureThreshold

    /**
     * Records a failure, grows the delay and, when [callback] is supplied and nothing is pending
     * yet, arms the retry. Returns the delay that is now in effect.
     */
    @JvmOverloads
    @Synchronized
    fun fail(callback: (() -> Unit)? = null): Long {
        fails++
        val jitterFactor = if (jitter) Math.random() else 1.0
        current = min(current + (2 * current * jitterFactor).toLong(), maxBackoffMs)
        if (callback != null && !isPending) {
            isPending = true
            scheduler.schedule({ executeFailureCallback(callback) }, current)
        }
        return current
    }

    /** Disarms a pending retry without resetting the accumulated delay. */
    @Synchronized
    fun cancel() {
        scheduler.cancel()
        isPending = false
    }

    /** Disarms a pending retry and resets the delay back to [minBackoffMs]. */
    @Synchronized
    fun succeed() {
        cancel()
        fails = 0
        current = minBackoffMs
    }

    @Synchronized
    private fun executeFailureCallback(callback: () -> Unit) {
        isPending = false
        callback()
    }

    /**
     * Default [Scheduler]: a single [Timer] that keeps at most one armed task. When a
     * [delegateExecutor] is supplied the callback is re-posted onto it, so backoff continuations run
     * on the owner's serialised thread instead of the timer thread.
     */
    class TimerScheduler @JvmOverloads constructor(
        private val tag: String = "TimerScheduler",
        private val delegateExecutor: ExecutorService? = null,
    ) : Timer(tag, true), Scheduler {

        private var timeoutTimerTask: TimerTask? = null

        @Synchronized
        override fun schedule(action: () -> Unit, delayMs: Long) {
            timeoutTimerTask?.cancel()
            val task = object : TimerTask() {
                override fun run() {
                    val executor = delegateExecutor
                    if (executor == null) {
                        action()
                    } else if (!executor.isShutdown) {
                        executor.execute(action)
                    }
                }
            }
            timeoutTimerTask = task
            schedule(task, delayMs.coerceAtLeast(0))
        }

        @Synchronized
        override fun cancel() {
            timeoutTimerTask?.cancel()
            timeoutTimerTask = null
        }
    }

    companion object {
        private const val DEFAULT_MIN_BACKOFF_MS = 500L
    }
}
