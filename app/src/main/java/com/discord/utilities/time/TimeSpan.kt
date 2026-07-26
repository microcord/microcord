package com.discord.utilities.time

import java.util.concurrent.TimeUnit

/** A duration carrying its own unit, so callers can add and convert without losing precision. */
data class TimeSpan(val amount: Long, val unit: TimeUnit) {

    fun toDays(): Long = unit.toDays(amount)
    fun toHours(): Long = unit.toHours(amount)
    fun toMinutes(): Long = unit.toMinutes(amount)
    fun toSeconds(): Long = unit.toSeconds(amount)
    fun toMillis(): Long = unit.toMillis(amount)
    fun toMicros(): Long = unit.toMicros(amount)
    fun toNanos(): Long = unit.toNanos(amount)

    // Arithmetic normalises to nanoseconds so mixed-unit operands stay exact.
    operator fun plus(b: TimeSpan): TimeSpan = TimeSpan(toNanos() + b.toNanos(), TimeUnit.NANOSECONDS)

    operator fun minus(b: TimeSpan): TimeSpan =
        TimeSpan(toNanos() - b.toNanos(), TimeUnit.NANOSECONDS)
}
