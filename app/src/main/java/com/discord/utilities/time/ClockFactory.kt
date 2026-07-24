package com.discord.utilities.time

object ClockFactory {
    private lateinit var clock: Clock

    fun init() {
        clock = Clock(System::currentTimeMillis)
    }

    @JvmStatic
    fun get(): Clock = clock
}
