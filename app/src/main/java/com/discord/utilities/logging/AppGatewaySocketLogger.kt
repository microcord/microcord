package com.discord.utilities.logging

import com.discord.gateway.GatewaySocketLogger

class AppGatewaySocketLogger : GatewaySocketLogger {
    override val logLevel = GatewaySocketLogger.LogLevel.NONE

    override fun logInboundMessage(rawMessage: String) = Unit

    override fun logMessageInflateFailed(throwable: Throwable) = Unit

    override fun logOutboundMessage(rawMessage: String) = Unit

    companion object {
        val INSTANCE = AppGatewaySocketLogger()
    }
}
