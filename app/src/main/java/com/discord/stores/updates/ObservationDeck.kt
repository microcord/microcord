package com.discord.stores.updates

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
        observers.add(observer)
        if (updateOnConnect) observer.onUpdate()
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
            override val name = observerName
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
        observer.markStale()
    }

    @Synchronized
    fun notify(updates: Set<UpdateSource>) {
        try {
            var index = 0
            while (index < observers.size) {
                val observer = observers[index]
                if (observer.isStale) {
                    observers.removeAt(index)
                } else {
                    if (updates.any(observer.observingUpdates::contains)) observer.onUpdate()
                    index++
                }
            }
        } catch (throwable: Throwable) {
            if (logLevel == LogLevel.NONE) throw throwable
        }
    }
}
