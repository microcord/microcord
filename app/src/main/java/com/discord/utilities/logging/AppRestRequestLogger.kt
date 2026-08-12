package com.discord.utilities.logging

import com.discord.utilities.cache.SharedPreferencesProvider

object AppRestRequestLogger {
    const val PREF_LOG_REST_REQUESTS = "developer_log_rest_requests"

    /** Session-durable: a switch of accounts is not a reason to stop logging this device. */
    fun isEnabled() = runCatching {
        SharedPreferencesProvider.getPrefsSessionDurable().getBoolean(PREF_LOG_REST_REQUESTS, false)
    }.getOrDefault(false)

    fun setEnabled(enabled: Boolean) {
        SharedPreferencesProvider.getPrefsSessionDurable().edit()
            .putBoolean(PREF_LOG_REST_REQUESTS, enabled).apply()
    }
}
