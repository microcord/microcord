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
        private const val PREF_LOG_GATEWAY_LIFECYCLE = "developer_log_gateway_lifecycle"
        private const val PREF_LOG_GATEWAY_VOICE_STATES = "developer_log_gateway_voice_states"
        private const val TAG = "GatewayEvents"
        val INSTANCE = AppGatewaySocketLogger()

        /** Session-durable: a switch of accounts is not a reason to stop logging this device. */
        fun isEnabled() = runCatching {
            SharedPreferencesProvider.getPrefsSessionDurable().getBoolean(PREF_LOG_GATEWAY_EVENTS, false)
        }.getOrDefault(false)

        fun setEnabled(enabled: Boolean) {
            SharedPreferencesProvider.getPrefsSessionDurable().edit()
                .putBoolean(PREF_LOG_GATEWAY_EVENTS, enabled).apply()
        }

        fun isLifecycleEnabled() = runCatching {
            SharedPreferencesProvider.getPrefsSessionDurable().getBoolean(PREF_LOG_GATEWAY_LIFECYCLE, false)
        }.getOrDefault(false)

        fun setLifecycleEnabled(enabled: Boolean) {
            SharedPreferencesProvider.getPrefsSessionDurable().edit()
                .putBoolean(PREF_LOG_GATEWAY_LIFECYCLE, enabled).apply()
        }

        fun isVoiceStateLoggingEnabled() = runCatching {
            SharedPreferencesProvider.getPrefsSessionDurable().getBoolean(PREF_LOG_GATEWAY_VOICE_STATES, false)
        }.getOrDefault(false)

        fun setVoiceStateLoggingEnabled(enabled: Boolean) {
            SharedPreferencesProvider.getPrefsSessionDurable().edit()
                .putBoolean(PREF_LOG_GATEWAY_VOICE_STATES, enabled).apply()
        }
    }
}
