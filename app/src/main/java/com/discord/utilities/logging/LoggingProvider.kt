package com.discord.utilities.logging

object LoggingProvider {
    private lateinit var logger: Logger

    fun get(): Logger = logger

    fun init(logger: Logger) {
        this.logger = logger
    }
}
