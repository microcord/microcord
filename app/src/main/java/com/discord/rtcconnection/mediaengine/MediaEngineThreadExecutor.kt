package com.discord.rtcconnection.mediaengine

import android.os.Handler
import android.os.Looper
import android.os.Process
import com.discord.app.AppLog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * The single thread every media-engine call is made on, plus an assertion that says so.
 *
 * The native engine is not thread-safe and the callers are anything but single-threaded: the voice
 * WebSocket delivers on an OkHttp thread, the engine's own callbacks arrive on native threads, and
 * the UI toggles mute from the main thread. Funnelling all of them here is what makes the rest of
 * the voice stack free of locks.
 *
 * [assertOnEngineThread] compares the *Linux* thread id rather than the `Thread` identity, because
 * native callbacks re-enter the JVM on the same OS thread but not necessarily through the same
 * `Thread` object. A violation is always logged; with [crashOnViolation] set it is also rethrown on
 * the main looper so it cannot be ignored in a debug build.
 *
 * It is an [ExecutorService] because [com.discord.utilities.networking.Backoff.TimerScheduler]
 * takes one, which is how reconnect timers land back on this thread instead of the timer thread.
 *
 * Reference: `b.a.q.c`.
 */
class MediaEngineThreadExecutor(
    private val delegate: ExecutorService,
    private val crashOnViolation: Boolean = false,
) : ExecutorService by delegate {

    @Volatile
    private var engineThreadId: Int = UNKNOWN_THREAD_ID

    init {
        delegate.submit {
            engineThreadId = Process.myTid()
            Thread.currentThread().name = "MediaEngineExecutor-${instanceCount.getAndIncrement()}"
        }
    }

    fun assertOnEngineThread() {
        if (Process.myTid() == engineThreadId) return
        val violation = IllegalStateException("oops! not called on the MediaEngineExecutor")
        AppLog.e(TAG, "oops! not called on the MediaEngineExecutor", violation)
        if (crashOnViolation) {
            Handler(Looper.getMainLooper()).post { throw violation }
        }
    }

    /** Runs [action] on the engine thread. Safe to call from any thread. */
    fun run(action: () -> Unit): Future<*> = delegate.submit(action)

    companion object {
        private const val TAG = "MediaEngineExecutor"
        private const val UNKNOWN_THREAD_ID = -1
        private val instanceCount = AtomicInteger()

        /** One daemon thread, named by the executor itself once it starts. */
        fun create(crashOnViolation: Boolean = false): MediaEngineThreadExecutor =
            MediaEngineThreadExecutor(
                Executors.newSingleThreadExecutor { runnable -> Thread(runnable).apply { isDaemon = true } },
                crashOnViolation,
            )
    }
}
