package com.discord.utilities.logging

import com.discord.gateway.GatewaySocketLogger
import org.junit.Assert.assertEquals
import org.junit.Test

class LoggerTest {
    @Test
    fun defaultTagOverloadsDelegateToTaggedMethods() {
        val calls = mutableListOf<Triple<String, String, Throwable?>>()
        val logger = object : Logger("default") {
            override fun d(tag: String, message: String, throwable: Throwable?) {
                calls += Triple(tag, message, throwable)
            }
        }
        val error = IllegalStateException("failure")

        logger.d("message", error)

        assertEquals(listOf(Triple("default", "message", error)), calls)
        assertEquals(GatewaySocketLogger.LogLevel.NONE, AppGatewaySocketLogger { false }.logLevel)
        assertEquals(GatewaySocketLogger.LogLevel.VERBOSE, AppGatewaySocketLogger { true }.logLevel)
    }
}
