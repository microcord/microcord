package com.discord.widgets.friends

import androidx.fragment.app.FragmentActivity
import java.nio.charset.Charset
import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * Discovers other Discord users physically nearby by broadcasting a short token and listening for
 * the same token from others.
 *
 * Reconstruction of the reference `com.discord.widgets.friends.NearbyManager`.
 *
 * ### Privacy
 * The only thing broadcast is `u:<own user id>`; no name, avatar or location ever leaves the
 * device. Discovered ids are held in memory for the duration of the scan and are **never logged**
 * — not at debug level either. [disableNearby] clears the set, and the reference calls it from
 * `onPause`, so the scan cannot outlive the visible screen. Nothing here sends a friend request:
 * the user has to tap a specific row.
 *
 * ### Transport
 * The reference drives Google Play Services' Nearby Messages API
 * (`com.google.android.gms.nearby.messages`, artifact `com.google.android.gms:play-services-nearby`),
 * which asks for its own location/Bluetooth consent through a system dialog and reports the answer
 * back via a status callback. That dependency is **not installed in this project** and the Nearby
 * Messages backend has since been retired, so the transport is expressed as [Transport] and left
 * uninstalled by default: [state] then reports [NearbyState.Disconnected] with
 * [PERMISSION_DENIED], which is exactly what the reference reports when the user declines the
 * permission — the screen shows the "Nearby connection was stopped" copy and its retry button.
 *
 * Installing a real transport is a one-liner ([Transport.factory]); the state machine, message
 * encoding, de-duplication and self-filtering below are all reconstructed and transport-agnostic.
 */
class NearbyManager {

    /**
     * What the state machine needs from a publish/subscribe transport.
     *
     * Deliberately narrow: publish one opaque payload, subscribe to others', and report whether the
     * user granted the transport its permissions.
     */
    interface Transport {
        /**
         * @param onFound receives a payload seen nearby.
         * @param onLost receives a payload that has gone out of range.
         * @param onPermissionChanged reports the transport's own consent state.
         * @param onExpired the publication timed out and should be renewed.
         */
        fun publishAndSubscribe(
            activity: FragmentActivity,
            payload: ByteArray,
            onFound: (ByteArray) -> Unit,
            onLost: (ByteArray) -> Unit,
            onPermissionChanged: (Boolean) -> Unit,
            onExpired: () -> Unit,
        )

        /** Stops publishing and unsubscribes. Must be safe to call when never started. */
        fun stop()

        companion object {
            /**
             * Install a Play Services backed implementation here during app start-up to turn the
             * nearby scan on. `null` means "no transport available on this device", which the
             * state machine surfaces as [PERMISSION_DENIED].
             */
            @JvmStatic
            @Volatile
            var factory: (() -> Transport)? = null
        }
    }

    sealed class NearbyState {
        object Uninitialized : NearbyState()

        data class Connected(val nearbyUserIds: Set<Long>) : NearbyState()

        /** @param code one of [CONNECTION_ERROR] / [PERMISSION_DENIED]. */
        data class Disconnected(val code: Int) : NearbyState()
    }

    private val nearbyStateSubject: BehaviorSubject<NearbyState> =
        BehaviorSubject.create(NearbyState.Uninitialized)

    private val nearbyUserIds = HashSet<Long>()

    private var meUserId: Long? = null
    private var outboundMessage: ByteArray? = null
    private var transport: Transport? = null

    val state: Observable<NearbyState> get() = nearbyStateSubject

    /** Must be called before [buildClientAndPublish]; supplies the id that is broadcast. */
    fun initialize(userId: Long) {
        meUserId = userId
        outboundMessage = (MESSAGE_PREFIX + userId).toByteArray(CHARSET)
    }

    fun buildClientAndPublish(fragmentActivity: FragmentActivity) {
        if (transport == null) transport = Transport.factory?.invoke()
        activateNearby(fragmentActivity)
    }

    fun activateNearby(fragmentActivity: FragmentActivity) {
        val transport = this.transport
        val payload = outboundMessage
        if (transport == null || payload == null) {
            nearbyStateSubject.onNext(NearbyState.Disconnected(PERMISSION_DENIED))
            return
        }
        nearbyStateSubject.onNext(NearbyState.Connected(HashSet(nearbyUserIds)))
        transport.publishAndSubscribe(
            activity = fragmentActivity,
            payload = payload,
            onFound = { message -> parseUserId(message)?.let(::foundUserId) },
            onLost = { message -> parseUserId(message)?.let(::lostUserId) },
            onPermissionChanged = { granted ->
                if (granted) {
                    nearbyStateSubject.onNext(NearbyState.Connected(HashSet(nearbyUserIds)))
                } else {
                    nearbyStateSubject.onNext(NearbyState.Disconnected(PERMISSION_DENIED))
                }
            },
            // The reference re-publishes on expiry rather than letting the scan die silently.
            onExpired = { activateNearby(fragmentActivity) },
        )
    }

    fun disableNearby() {
        transport?.stop()
        synchronized(this) { nearbyUserIds.clear() }
        nearbyStateSubject.onNext(NearbyState.Uninitialized)
    }

    /** Own id is filtered out: the device always hears its own broadcast. */
    private fun foundUserId(userId: Long) = synchronized(this) {
        if (userId == meUserId) return@synchronized
        nearbyUserIds.add(userId)
        nearbyStateSubject.onNext(NearbyState.Connected(HashSet(nearbyUserIds)))
    }

    private fun lostUserId(userId: Long) = synchronized(this) {
        nearbyUserIds.remove(userId)
        nearbyStateSubject.onNext(NearbyState.Connected(HashSet(nearbyUserIds)))
    }

    companion object {
        /** The transport reported a failure it could not recover from. */
        const val CONNECTION_ERROR = 99

        /** The user declined the transport's location/Bluetooth consent, or it is unavailable. */
        const val PERMISSION_DENIED = 98

        private const val MESSAGE_PREFIX = "u:"
        private val CHARSET: Charset = Charsets.UTF_8

        /**
         * Payloads are `u:<userId>`. Anything that does not start with the prefix, or whose tail is
         * not a number, is ignored — the transport is a shared broadcast medium and other apps'
         * messages can arrive here.
         */
        internal fun parseUserId(message: ByteArray): Long? {
            val text = String(message, CHARSET)
            if (!text.startsWith(MESSAGE_PREFIX)) return null
            return text.substring(MESSAGE_PREFIX.length).toLongOrNull()
        }
    }
}
