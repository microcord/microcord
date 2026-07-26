package com.discord.utilities.time

import com.lyft.kronos.KronosClock

/**
 * A [Clock] backed by NTP.
 *
 * Message ordering, slow-mode countdowns, token expiry and rate-limit windows are all compared
 * against server timestamps, so a device whose clock is minutes off would render history in the
 * wrong order. Kronos keeps an offset against NTP and falls back to the device clock whenever it
 * has no fresh sample, so this never blocks or fails.
 */
class NtpClock(val kronosClock: KronosClock) : Clock {
    override fun currentTimeMillis(): Long = kronosClock.getCurrentTimeMs()
}
