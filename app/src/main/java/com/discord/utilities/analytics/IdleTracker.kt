package com.discord.utilities.analytics

import com.discord.stores.UserSettings
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * How long the client has gone unused, for the fields the heartbeat reports.
 *
 * On this platform backgrounding counts as idle outright, which is the reference's own
 * `isSystemIdle` branch for Android; the suspend and lock states it also checks there are desktop
 * power events with nothing to set them here.
 */
object IdleTracker {

    private val IDLE_DURATION_MS = TimeUnit.MINUTES.toMillis(10)

    @Volatile private var idleSince = System.currentTimeMillis()
    @Volatile private var backgrounded = false

    /** Channel and voice channel selection, and any change of app state. */
    fun markUsed() {
        idleSince = System.currentTimeMillis()
    }

    fun handleBackgrounded(backgrounded: Boolean) {
        this.backgrounded = backgrounded
        markUsed()
    }

    fun isIdle(): Boolean =
        backgrounded || System.currentTimeMillis() - idleSince > IDLE_DURATION_MS

    fun isAfk(): Boolean {
        val afkTimeoutMs = TimeUnit.SECONDS.toMillis(UserSettings.afkTimeout.get().toLong())
        if (afkTimeoutMs == 0L || backgrounded) return true
        return System.currentTimeMillis() - idleSince > min(afkTimeoutMs, IDLE_DURATION_MS)
    }

    /** Null unless idle, which is what makes the reported duration count from zero when it is not. */
    fun idleSince(): Long? = idleSince.takeIf { isIdle() }
}
