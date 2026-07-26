package com.discord.stores

import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.permissions.SlowModeType
import com.discord.utilities.time.Clock
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * Per-channel slow-mode cooldowns.
 *
 * Two independent cooldowns exist per channel and are tracked separately, because a channel's
 * `rate_limit_per_user` throttles posting a message *and* creating a thread, and being on
 * cooldown for one must not disable the other: see [Type].
 *
 * State is only ever "the earliest wall-clock time at which the next send is allowed". Everything
 * user-visible — the countdown in the chat input, the disabled send button — is derived from that
 * one timestamp by [observeCooldownSecs], so there is no timer to keep in sync and no cooldown to
 * leak when the app is backgrounded.
 */
class StoreSlowMode(
    private val clock: Clock,
    private val storeChannels: StoreChannels,
    private val storePermissions: StorePermissions,
) : Store() {

    /** Which of a channel's two independent cooldowns is being asked about. */
    sealed class Type {
        object MessageSend : Type()
        object ThreadCreate : Type()
    }

    private val messageSendNextSendTimes = HashMap<Long, Long>()
    private val messageSendNextSendTimesSubject =
        BehaviorSubject.create<Map<Long, Long>>(HashMap(messageSendNextSendTimes))

    private val threadCreateNextSendTimes = HashMap<Long, Long>()
    private val threadCreateNextSendTimesSubject =
        BehaviorSubject.create<Map<Long, Long>>(HashMap(threadCreateNextSendTimes))

    private val channelMessageSendCooldownObservables = HashMap<Long, Observable<Int>>()
    private val channelThreadCreateCooldownObservables = HashMap<Long, Observable<Int>>()

    /**
     * Seconds remaining before [channelId] accepts the next [type] action, ticking down to 0.
     *
     * A null [channelId] (no channel selected) is never on cooldown. The returned observable is
     * shared per channel+type and removes itself from the cache once the last subscriber leaves,
     * so a channel the user never opens costs nothing.
     */
    fun observeCooldownSecs(channelId: Long?, type: Type): Observable<Int> =
        if (channelId == null) Observable.just(0) else getChannelCooldownObservable(channelId, type)

    /**
     * Starts a cooldown of [cooldownMs] on [channelId].
     *
     * [COOLDOWN_BUFFER_MS] is added because the server's remaining time is measured against its
     * own clock; without the buffer a retry fired exactly on expiry is rejected again.
     */
    @StoreThread
    fun onCooldown(channelId: Long, cooldownMs: Long, type: Type) =
        onCooldownInternal(channelId, cooldownMs + COOLDOWN_BUFFER_MS, type)

    /** Applies the channel's configured slow mode after a message is accepted. */
    @StoreThread
    fun onMessageSent(channelId: Long) {
        val rateLimitPerUser = storeChannels.getChannel(channelId)?.rateLimitPerUser ?: 0
        if (rateLimitPerUser > 0) {
            onCooldownInternal(channelId, rateLimitPerUser * 1000L, Type.MessageSend)
        }
    }

    /** Applies the parent channel's slow mode after a thread is created inside it. */
    @StoreThread
    fun onThreadCreated(parentChannelId: Long) {
        val rateLimitPerUser = storeChannels.getChannel(parentChannelId)?.rateLimitPerUser ?: 0
        if (rateLimitPerUser > 0) {
            onCooldownInternal(parentChannelId, rateLimitPerUser * 1000L, Type.ThreadCreate)
        }
    }

    @StoreThread
    private fun onCooldownInternal(channelId: Long, cooldownMs: Long, type: Type) {
        val nextSendTime = clock.currentTimeMillis() + cooldownMs
        if (type == Type.MessageSend) {
            messageSendNextSendTimes[channelId] = nextSendTime
            messageSendNextSendTimesSubject.onNext(HashMap(messageSendNextSendTimes))
        } else {
            threadCreateNextSendTimes[channelId] = nextSendTime
            threadCreateNextSendTimesSubject.onNext(HashMap(threadCreateNextSendTimes))
        }
    }

    @Synchronized
    private fun getChannelCooldownObservable(channelId: Long, type: Type): Observable<Int> {
        val cache = observableCache(type)
        cache[channelId]?.let { return it }

        val nextSendTimes = when (type) {
            Type.MessageSend -> messageSendNextSendTimesSubject
            Type.ThreadCreate -> threadCreateNextSendTimesSubject
        }

        val cooldown = storePermissions.observePermissionsForChannel(channelId)
            .map { permissions -> PermissionUtils.hasBypassSlowmodePermissions(permissions, type.toPermissionType()) }
            .distinctUntilChanged()
            .switchMap { canBypass ->
                if (canBypass) {
                    Observable.just(0L)
                } else {
                    nextSendTimes.map { it[channelId] }.switchMap { nextSendTime -> countdown(nextSendTime) }
                }
            }
            .map { remainingMs -> (remainingMs / 1000L).toInt() }
            .doOnUnsubscribe { removeChannelCooldownObservable(channelId, type) }
            .replay(1)
            .refCount()

        cache[channelId] = cooldown
        return cooldown
    }

    /**
     * A 1 Hz countdown that terminates as soon as the remaining time goes negative.
     *
     * `now` is captured once so every tick is measured against the same origin: re-reading the
     * clock per tick would let a paused emulator or an NTP correction stall the countdown.
     */
    private fun countdown(nextSendTime: Long?): Observable<Long> {
        val now = clock.currentTimeMillis()
        if (nextSendTime == null || nextSendTime <= now) return Observable.just(0L)
        return Observable.interval(0L, 1L, TimeUnit.SECONDS)
            .map { tick -> nextSendTime - (now + tick * 1000L) }
            .filter { remainingMs -> remainingMs >= 0L }
    }

    @Synchronized
    private fun removeChannelCooldownObservable(channelId: Long, type: Type): Observable<Int>? =
        observableCache(type).remove(channelId)

    private fun observableCache(type: Type) = when (type) {
        Type.MessageSend -> channelMessageSendCooldownObservables
        Type.ThreadCreate -> channelThreadCreateCooldownObservables
    }

    private companion object {
        /**
         * Slack added to every server-reported cooldown.
         *
         * Reference `StoreSlowMode.COOLDOWN_BUFFER_MS`.
         */
        const val COOLDOWN_BUFFER_MS = 1_000L
    }
}

/**
 * Bridges to `PermissionUtils`' stand-in enum.
 *
 * `utilities/permissions/SlowModeType` was introduced while this store did not exist. It is
 * owned by another slice, so the mapping lives here; delete both once that enum is replaced by
 * [StoreSlowMode.Type].
 */
private fun StoreSlowMode.Type.toPermissionType() = when (this) {
    StoreSlowMode.Type.MessageSend -> SlowModeType.MESSAGE_SEND
    StoreSlowMode.Type.ThreadCreate -> SlowModeType.THREAD_CREATE
}
