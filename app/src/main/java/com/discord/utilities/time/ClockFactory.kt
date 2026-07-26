package com.discord.utilities.time

import android.app.Application
import com.lyft.kronos.AndroidClockFactory

/**
 * Process-wide source of the current time.
 *
 * Everything that timestamps or compares against server time goes through here rather than
 * `System.currentTimeMillis`, so the whole app shares one NTP-corrected clock. Until [init] runs
 * with an [Application] the device clock is used.
 */
object ClockFactory {

    private val systemClock = Clock { System.currentTimeMillis() }

    private var clock: Clock = systemClock

    @JvmStatic
    fun get(): Clock = clock

    /**
     * Installs the NTP-backed clock and kicks off the first synchronisation in the background.
     * Called once from application startup; without an [application] the device clock is kept.
     */
    @JvmOverloads
    fun init(application: Application? = null) {
        clock = if (application == null) {
            systemClock
        } else {
            NtpClock(AndroidClockFactory.createKronosClock(application).apply { syncInBackground() })
        }
    }
}
