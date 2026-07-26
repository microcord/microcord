package com.discord.gateway

import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * Registry of components that need the gateway socket held open while the app is backgrounded.
 *
 * A backgrounded client with no voice channel normally drops the socket to save battery. Anything
 * that must keep receiving events anyway — an active call overlay, a media projection, a pending
 * send — registers itself here, and `StoreGatewayConnection` keeps the socket alive while the
 * registry is non-empty.
 *
 * The reference keeps this in `com.discord.app.AppState`; it lives here so the gateway owns its own
 * lifetime input.
 */
object GatewayConnectionConsumers {
    private val consumers = ArrayList<Any>(4)
    private val count = BehaviorSubject.create(0)

    @Synchronized
    fun add(consumer: Any) {
        consumers.add(consumer)
        count.onNext(consumers.size)
    }

    @Synchronized
    fun remove(consumer: Any) {
        consumers.remove(consumer)
        count.onNext(consumers.size)
    }

    fun observeHasConsumers(): Observable<Boolean> = count.map { it > 0 }.distinctUntilChanged()
}
