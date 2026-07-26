package com.discord.utilities.time

/**
 * Time since [startTime], measured once and then cached.
 *
 * The value is latched on first read so every consumer of the same instance reports the same
 * elapsed time even when they read it at slightly different moments (analytics events built from
 * several fields, for example).
 */
class TimeElapsed(clock: Clock, private val startTime: Long = clock.currentTimeMillis()) {

    val milliseconds: Long by lazy { clock.currentTimeMillis() - startTime }

    val seconds: Float by lazy { milliseconds / 1000f }
}
