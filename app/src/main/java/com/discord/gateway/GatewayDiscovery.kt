package com.discord.gateway

import android.content.SharedPreferences
import androidx.core.content.edit
import com.discord.models.domain.ModelGateway
import java.net.URI

class GatewayDiscovery(
    private val preferences: SharedPreferences? = null,
    private val provider: () -> ModelGateway,
) {
    private var stickyUrl: String? = preferences?.getString(GATEWAY_URL_CACHE_KEY, null)?.takeIf(::isValid)

    fun discover(ignoreSticky: Boolean = false): String {
        if (!ignoreSticky) stickyUrl?.let { return it }
        val url = provider().url
        if (url == null || !isValid(url)) {
            invalidate()
            throw IllegalArgumentException("Malformed gateway URL")
        }
        stickyUrl = url
        preferences?.edit { putString(GATEWAY_URL_CACHE_KEY, url) }
        return url
    }

    fun invalidate() {
        stickyUrl = null
        preferences?.edit { remove(GATEWAY_URL_CACHE_KEY) }
    }

    private fun isValid(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme.equals("wss", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
    } catch (_: Exception) {
        false
    }

    companion object {
        const val GATEWAY_URL_CACHE_KEY = "GATEWAY_URL_CACHE_KEY"
    }
}
