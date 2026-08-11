package com.discord.stores

import com.google.protobuf.Message
import rx.Observable

/**
 * How long a write may sit before it is sent.
 *
 * Settings are written far more often than they are read — a drag through a guild folder or a
 * frecency bump would otherwise be one request per gesture — so each setting declares how urgent
 * its own writes are and they coalesce until then.
 */
enum class UserSettingsDelay(val seconds: Long) {
    /** Deliberate, visible changes: a theme switch should survive being killed a second later. */
    INFREQUENT_USER_ACTION(0),
    FREQUENT_USER_ACTION(10),
    SLOW_USER_ACTION(20),
    AUTOMATED(30),
    DAILY(86_400),
}

/**
 * One field of the settings proto, as the rest of the app sees it.
 *
 * The proto's default value is never the app's default: an unset `gif_auto_play` means "never
 * chosen", which renders as on. [fromProto] is where that lives, which is why reads go through
 * here rather than touching the proto.
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

    /** Reads out of [settings] rather than the store, for store-thread code running ahead of the snapshot. */
    fun get(settings: P): T = fromProto(settings)

    fun observe(): Observable<T> = store.observeSettings().map(fromProto)
        .distinctUntilChanged { a, b -> areEqual(a, b) }
        .doOnSubscribe { store.loadIfNecessary() }

    /** No-ops when the value already matches, so a redundant tap costs no request. */
    fun set(value: T) {
        if (areEqual(get(), value)) return
        writer.write(delay) { toProto(value) }
    }

    fun update(transform: (T) -> T) = set(transform(get()))
}
