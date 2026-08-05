package com.discord.utilities.logging

import com.discord.utilities.cache.SharedPreferencesProvider

object AppRestRequestLogger {
    const val PREF_LOG_REST_REQUESTS = "developer_log_rest_requests"

    fun isEnabled() = runCatching {
        SharedPreferencesProvider.get().getBoolean(PREF_LOG_REST_REQUESTS, false)
    }.getOrDefault(false)

    fun setEnabled(enabled: Boolean) {
        SharedPreferencesProvider.get().edit().putBoolean(PREF_LOG_REST_REQUESTS, enabled).apply()
    }
}
