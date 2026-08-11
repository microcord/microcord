package com.discord.stores

import com.google.protobuf.Message
import rx.Observable

/** How long a write may sit before it is sent, so a burst of gestures costs one request. */
enum class UserSettingsDelay(val seconds: Long) {
    INFREQUENT_USER_ACTION(0),
    FREQUENT_USER_ACTION(10),
    SLOW_USER_ACTION(20),
    AUTOMATED(30),
    DAILY(86_400),
}

/**
 * One field of the settings proto. An unset field means "never chosen", not the zero value, so
 * [fromProto] owns the app's default rather than the proto.
 */
class UserSettingsProtoSetting<P : Message, B : Message.Builder, T>(
    private val store: StoreUserSettingsProto<P>,
    private val writer: UserSettingsProtoWriter<P, B>,
    private val fromProto: (P) -> T,
    private val delay: UserSettingsDelay = UserSettingsDelay.INFREQUENT_USER_ACTION,
    private val areEqual: (T, T) -> Boolean = { a, b -> a == b },
    private val toProto: B.(T) -> Unit,
) {
    fun get(): T = fromProto(store.getSettings())

    /** For store-thread code running ahead of the snapshot. */
    fun get(settings: P): T = fromProto(settings)

    fun observe(): Observable<T> = store.observeSettings().map(fromProto)
        .distinctUntilChanged { a, b -> areEqual(a, b) }
        .doOnSubscribe { store.loadIfNecessary() }

    fun set(value: T) {
        if (areEqual(get(), value)) return
        writer.write(delay) { toProto(value) }
    }

    fun update(transform: (T) -> T) = set(transform(get()))
}
