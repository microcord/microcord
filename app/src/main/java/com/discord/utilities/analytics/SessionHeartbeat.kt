package com.discord.utilities.analytics

import android.os.SystemClock
import com.discord.gateway.io.HeartbeatQos
import com.discord.utilities.cache.SharedPreferencesProvider
import com.google.gson.Gson
import java.util.UUID
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Subscription

/**
 * The client's "time spent" session, and the heartbeats that report it.
 *
 * A session is not a launch: it survives a restart and turns over only after thirty minutes of
 * disuse, but never survives a change of account.
 */
object SessionHeartbeat {

    private const val VERSION = 29
    private const val STORAGE_VERSION = 1
    private const val STORAGE_KEY = "LAST_CLIENT_HEARTBEAT_SESSION"
    private val FREQUENCY_MS = TimeUnit.MINUTES.toMillis(15)
    private val IDLE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30)
    private val STORAGE_FLUSH_INTERVAL_MS = TimeUnit.SECONDS.toMillis(1)

    data class ClientSession(
        val uuid: String,
        val createdAtTimestamp: Long,
        var lastUsedTimestamp: Long,
        val version: Int = STORAGE_VERSION,
    ) {
        fun isExpired(now: Long) = lastUsedTimestamp + IDLE_TIMEOUT_MS <= now
    }

    private val gson = Gson()

    @Volatile private var token: String? = null
    @Volatile private var foregrounded = true
    @Volatile private var rtcConnected = false

    private var session: ClientSession? = null
    private var loaded = false
    private var lastFlushedAt = 0L
    private var lastHeartbeatAt = 0L
    private var timer: Subscription? = null

    val clientLaunchId: String = UUID.randomUUID().toString()

    @Volatile
    var onActiveStateChanged: (HeartbeatQos) -> Unit = {}

    @Volatile
    var onSessionIdChanged: (Long, String, String) -> Unit = { _, _, _ -> }

    fun handleAuthTokenChanged(newToken: String?) {
        if (token == newToken) return
        token = newToken
        resetSession()
        scheduleHeartbeatTracking()
    }

    fun handleBackgrounded(backgrounded: Boolean) {
        if (foregrounded == !backgrounded) return
        foregrounded = !backgrounded
        scheduleHeartbeatTracking()
    }

    fun handleRtcConnected(connected: Boolean) {
        if (rtcConnected == connected) return
        rtcConnected = connected
        scheduleHeartbeatTracking()
    }

    /** `READY`; a new gateway session has no memory of the time-spent one. */
    fun handleConnectionOpen() {
        val session = getSession(dispatchIfUpdated = false) ?: return
        onSessionIdChanged(session.createdAtTimestamp, session.uuid, clientLaunchId)
    }

    /** Read-only: stamping a header is not use, so it must not touch or mint. */
    @Synchronized
    fun activeSessionUnsafe(): ClientSession? =
        load()?.takeUnless { it.isExpired(System.currentTimeMillis()) }

    @Synchronized
    fun getSession(dispatchIfUpdated: Boolean = true): ClientSession? {
        val previousId = session?.uuid
        var current = load()
        val now = System.currentTimeMillis()
        if (isActive()) {
            if (current == null || current.isExpired(now)) {
                current = ClientSession(UUID.randomUUID().toString(), now, now)
                lastFlushedAt = 0L
            }
            current.lastUsedTimestamp = now
            maybeFlush(current)
        } else if (current != null && current.isExpired(now)) {
            current = null
        }
        session = current
        loaded = true
        if (current != null && previousId != current.uuid && dispatchIfUpdated) {
            onSessionIdChanged(current.createdAtTimestamp, current.uuid, clientLaunchId)
        }
        return current
    }

    /** Being in a call counts as use even while backgrounded. */
    private fun buildQos(): HeartbeatQos {
        val reasons = mutableListOf<String>()
        if (token != null) {
            if (foregrounded) reasons.add("foregrounded")
            if (rtcConnected) reasons.add("rtc_connected")
        }
        return HeartbeatQos(active = reasons.isNotEmpty(), ver = VERSION, reasons = reasons)
    }

    private fun isActive() = buildQos().active

    @Synchronized
    private fun scheduleHeartbeatTracking() {
        if (isActive()) start() else stop()
        onActiveStateChanged(buildQos())
    }

    private fun start() {
        if (timer != null) return
        // Resumes the period rather than restarting it.
        val delay = if (lastHeartbeatAt == 0L) {
            0L
        } else {
            (FREQUENCY_MS - (SystemClock.elapsedRealtime() - lastHeartbeatAt)).coerceAtLeast(0L)
        }
        timer = Observable.interval(delay, FREQUENCY_MS, TimeUnit.MILLISECONDS)
            .subscribe({ trackHeartbeat() }, {})
    }

    private fun stop() {
        timer?.unsubscribe()
        timer = null
    }

    private fun trackHeartbeat() {
        val session = getSession() ?: return
        AnalyticsUtils.Tracker.instance.track(
            "client_heartbeat",
            mapOf(
                "client_heartbeat_initialization_timestamp" to session.createdAtTimestamp,
                "client_heartbeat_version" to VERSION,
                "is_idle" to IdleTracker.isIdle(),
                "idle_duration_ms" to System.currentTimeMillis() - (IdleTracker.idleSince() ?: 0L),
                "is_afk" to IdleTracker.isAfk(),
                // Desktop power events; nothing on this platform ever sets them, as in the reference.
                "is_system_suspended" to false,
                "is_system_locked" to false,
            ),
        )
        lastHeartbeatAt = SystemClock.elapsedRealtime()
    }

    private fun resetSession() {
        preferences()?.edit()?.remove(STORAGE_KEY)?.apply()
        session = null
        loaded = true
        stop()
        lastHeartbeatAt = 0L
    }

    private fun load(): ClientSession? {
        if (loaded) return session
        loaded = true
        val raw = preferences()?.getString(STORAGE_KEY, null)
        session = raw
            ?.let { runCatching { gson.fromJson(it, ClientSession::class.java) }.getOrNull() }
            ?.takeIf { it.version == STORAGE_VERSION }
        return session
    }

    private fun maybeFlush(session: ClientSession) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFlushedAt < STORAGE_FLUSH_INTERVAL_MS) return
        preferences()?.edit()?.putString(STORAGE_KEY, gson.toJson(session))?.apply()
        lastFlushedAt = now
    }

    private fun preferences() = runCatching { SharedPreferencesProvider.get() }.getOrNull()
}
