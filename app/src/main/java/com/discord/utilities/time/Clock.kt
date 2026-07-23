package com.discord.utilities.time

fun interface Clock {
    fun currentTimeMillis(): Long
}
