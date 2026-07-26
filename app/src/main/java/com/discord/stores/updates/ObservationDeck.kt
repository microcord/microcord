package com.discord.stores.updates

import android.util.Log
import rx.Emitter
import rx.Observable

class ObservationDeck(
    private val logLevel: LogLevel = LogLevel.NONE,
) {
    private val observers = ArrayList<Observer>()

    interface UpdateSource

    enum class LogLevel { NONE, ERROR, VERBOSE }

    abstract class Observer {
        abstract val observingUpdates: Set<UpdateSource>
        abstract var onUpdate: () -> Unit
        abstract val name: String?

        var isStale: Boolean = false
            private set

        fun markStale() {
            isStale = true
            onUpdate = {}
        }

        fun toDebugLogString() =
            "Observer name: ${name ?: "Unknown"}\n${observingUpdates.joinToString()}"
    }

    @Synchronized
    @JvmOverloads
    fun connect(observer: Observer, updateOnConnect: Boolean = true): Observer {
        logBreadcrumb("connect START. observer: ${observer.name}")
        observers.add(observer)
        if (updateOnConnect) observer.onUpdate()
        logBreadcrumb("connect END. observer: ${observer.name} -- isStale: ${observer.isStale}")
        return observer
    }

    @Synchronized
    @JvmOverloads
    fun connect(
        vararg updateSources: UpdateSource,
        updateOnConnect: Boolean = true,
        observerName: String? = null,
        onUpdate: () -> Unit,
    ): Observer = connect(
        object : Observer() {
            override val observingUpdates = updateSources.toSet()
            override var onUpdate = onUpdate
            override val name = observerName ?: hashCode().toString()
        },
        updateOnConnect,
    )

    @Synchronized
    @JvmOverloads
    fun connectRx(
        vararg updateSources: UpdateSource,
        updateOnConnect: Boolean = true,
        backpressureMode: Emitter.BackpressureMode = Emitter.BackpressureMode.LATEST,
        observerName: String? = null,
    ): Observable<Unit> = Observable.create<Unit>(
        { emitter ->
            val observer = connect(*updateSources, updateOnConnect = updateOnConnect, observerName = observerName) {
                emitter.onNext(Unit)
            }
            emitter.setCancellation { disconnect(observer) }
        },
        backpressureMode,
    )

    @Synchronized
    @JvmOverloads
    fun <T> connectRx(
        vararg updateSources: UpdateSource,
        updateOnConnect: Boolean = true,
        backpressureMode: Emitter.BackpressureMode = Emitter.BackpressureMode.LATEST,
        observerName: String? = null,
        generator: () -> T,
    ): Observable<T> = connectRx(
        *updateSources,
        updateOnConnect = updateOnConnect,
        backpressureMode = backpressureMode,
        observerName = observerName,
    ).map { generator() }

    @Synchronized
    fun disconnect(observer: Observer) {
        logBreadcrumb("disconnect START. observer: ${observer.name}")
        observer.markStale()
        logBreadcrumb("disconnect END. observer: ${observer.name}")
    }

    @Synchronized
    fun notify(updates: Set<UpdateSource>) {
        logBreadcrumb("notify START")
        try {
            var index = 0
            while (index < observers.size) {
                val observer = observers[index]
                if (observer.isStale) {
                    logBreadcrumb("removing observer: ${observer.name}")
                    observers.removeAt(index)
                    index--
                } else if (updates.any(observer.observingUpdates::contains)) {
                    observer.onUpdate()
                }
                index++
            }
        } catch (throwable: Throwable) {
            // Only a deck configured to report errors absorbs them; otherwise the failing
            // observer must surface to the caller instead of being silently dropped.
            if (logLevel >= LogLevel.ERROR) logNotifyError(throwable, updates) else throw throwable
        } finally {
            logBreadcrumb("notify END")
        }
    }

    private fun logBreadcrumb(message: String) {
        if (logLevel == LogLevel.VERBOSE) Log.d(LOG_CATEGORY, message)
    }

    private fun logNotifyError(throwable: Throwable, updates: Set<UpdateSource>) {
        val affected = observers.filter { observer -> updates.any(observer.observingUpdates::contains) }
        Log.e(
            LOG_CATEGORY,
            "ObservationDeck notify error\n" +
                "Update Sources: ${updates.joinToString()}\n" +
                "Observers: ${affected.joinToString("\n", transform = Observer::toDebugLogString)}",
            throwable,
        )
    }

    private companion object {
        private const val LOG_CATEGORY = "ObservationDeck"
    }
}
