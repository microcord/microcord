package com.discord.utilities.logging

import com.discord.app.AppLog
import com.discord.gateway.GatewayLogRedactor
import com.discord.gateway.GatewaySocketLogger
import com.discord.utilities.cache.SharedPreferencesProvider

class AppGatewaySocketLogger(
    private val enabled: () -> Boolean = ::isEnabled,
) : GatewaySocketLogger {
    override val logLevel: GatewaySocketLogger.LogLevel
        get() = if (enabled()) GatewaySocketLogger.LogLevel.VERBOSE else GatewaySocketLogger.LogLevel.NONE

    override fun logInboundMessage(rawMessage: String) {
        if (enabled()) AppLog.d(TAG, "IN ${GatewayLogRedactor.redact(rawMessage)}")
    }

    override fun logMessageInflateFailed(throwable: Throwable) {
        if (enabled()) AppLog.e(TAG, "Failed to inflate inbound gateway message", throwable)
    }

    override fun logOutboundMessage(rawMessage: String) {
        if (enabled()) AppLog.d(TAG, "OUT ${GatewayLogRedactor.redact(rawMessage)}")
    }

    companion object {
        const val PREF_LOG_GATEWAY_EVENTS = "developer_log_gateway_events"
        private const val TAG = "GatewayEvents"
        val INSTANCE = AppGatewaySocketLogger()

        fun isEnabled() = runCatching {
            SharedPreferencesProvider.get().getBoolean(PREF_LOG_GATEWAY_EVENTS, false)
        }.getOrDefault(false)

        fun setEnabled(enabled: Boolean) {
            SharedPreferencesProvider.get().edit().putBoolean(PREF_LOG_GATEWAY_EVENTS, enabled).apply()
        }
    }
}
