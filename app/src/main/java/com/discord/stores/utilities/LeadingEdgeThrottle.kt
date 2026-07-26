package com.discord.stores.utilities

import rx.Observable
import rx.Scheduler
import rx.Subscriber
import rx.exceptions.Exceptions
import rx.observers.SerializedSubscriber
import rx.schedulers.Schedulers
import rx.subscriptions.SerialSubscription
import java.util.concurrent.TimeUnit

/**
 * Debounce that emits on the leading edge of a window instead of only the trailing edge.
 *
 * The first value of a burst is delivered immediately; anything arriving inside the following
 * [windowDuration] is collapsed to a single trailing emission of the newest value. Stores use
 * this so a screen renders instantly and then repaints at most once per window while a burst
 * of dispatches is running.
 */
class LeadingEdgeThrottle<T>(
    windowDuration: Long,
    timeUnit: TimeUnit,
    private val scheduler: Scheduler = Schedulers.computation(),
) : Observable.Operator<T, T> {
    private val timeInMilliseconds = timeUnit.toMillis(windowDuration)

    override fun call(child: Subscriber<in T>): Subscriber<in T> {
        val worker = scheduler.createWorker()
        val serialized = SerializedSubscriber<T>(child)
        val serial = SerialSubscription()
        serialized.add(worker)
        serialized.add(serial)

        return object : Subscriber<T>(child) {
            private val state = DebounceState<T>()
            private var lastOnNext = NEVER

            override fun onStart() = request(Long.MAX_VALUE)

            override fun onNext(value: T) {
                val now = scheduler.now()
                val index = state.next(value)
                val previous = lastOnNext
                if (previous != NEVER && now - previous < timeInMilliseconds) {
                    serial.set(
                        worker.schedule(
                            { state.emit(index, serialized, this) },
                            timeInMilliseconds,
                            TimeUnit.MILLISECONDS,
                        ),
                    )
                    return
                }
                lastOnNext = now
                state.emit(index, serialized, this)
            }

            override fun onError(e: Throwable) {
                serialized.onError(e)
                unsubscribe()
                state.clear()
            }

            override fun onCompleted() = state.emitAndComplete(serialized, this)
        }
    }

    /**
     * Holds the newest pending value together with a monotonically increasing index so a
     * scheduled trailing emission drops itself once a newer value has arrived.
     */
    private class DebounceState<T> {
        private var value: T? = null
        private var hasValue = false
        private var index = 0
        private var emitting = false
        private var terminate = false

        @Synchronized
        fun next(value: T): Int {
            this.value = value
            hasValue = true
            return ++index
        }

        @Synchronized
        fun clear() {
            index++
            value = null
            hasValue = false
        }

        @Suppress("UNCHECKED_CAST")
        fun emit(index: Int, child: Subscriber<in T>, self: Subscriber<*>) {
            var emitted: T? = null
            synchronized(this) {
                if (emitting || !hasValue || index != this.index) return
                emitted = value
                value = null
                hasValue = false
                emitting = true
            }
            try {
                child.onNext(emitted as T)
            } catch (throwable: Throwable) {
                Exceptions.throwOrReport(throwable, self, emitted)
                return
            }
            synchronized(this) {
                if (terminate) child.onCompleted() else emitting = false
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun emitAndComplete(child: Subscriber<in T>, self: Subscriber<*>) {
            var emitted: T? = null
            var shouldEmit = false
            synchronized(this) {
                if (emitting) {
                    terminate = true
                    return
                }
                emitted = value
                shouldEmit = hasValue
                value = null
                hasValue = false
                emitting = true
            }
            if (shouldEmit) {
                try {
                    child.onNext(emitted as T)
                } catch (throwable: Throwable) {
                    Exceptions.throwOrReport(throwable, self, emitted)
                    return
                }
            }
            child.onCompleted()
        }
    }

    private companion object {
        private const val NEVER = -1L
    }
}
