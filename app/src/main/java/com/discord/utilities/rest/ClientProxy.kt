package com.discord.utilities.rest

import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.feature.ClientSettings
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * The user's HTTP or SOCKS proxy, applied to every connection the app makes through Java.
 *
 * Installed as the process-wide [ProxySelector] before any client exists. OkHttp clients (REST,
 * Gateway, voice signalling, images, updater, plugins) and `HttpURLConnection` (media playback)
 * all consult that selector, so one switch covers them. The selector reads the setting on every
 * new connection; connections already open keep their route until the app restarts.
 *
 * Not covered: voice and video media (UDP inside the native engine), WebViews, and the system
 * download manager.
 *
 * While a proxy is set the selector never offers a direct route, so a dead proxy fails the request
 * rather than silently leaking the real address. Loopback hosts always go direct.
 */
object ClientProxy {
    enum class Type(val id: String, val javaType: Proxy.Type) {
        HTTP("http", Proxy.Type.HTTP),
        SOCKS("socks", Proxy.Type.SOCKS),
    }

    data class Config(val type: Type, val host: String, val port: Int) {
        init {
            require(host.isNotBlank()) { "host" }
            require(port in 1..MAX_PORT) { "port" }
        }
    }

    const val MAX_PORT = 65535

    fun current(): Config? {
        val preferences = SharedPreferencesProvider.getOrNull() ?: return null
        val type = ClientSettings.proxyType.get(preferences)?.let { id -> Type.entries.firstOrNull { it.id == id } }
            ?: return null
        val host = ClientSettings.proxyHost.get(preferences)?.trim().orEmpty()
        return runCatching { Config(type, host, ClientSettings.proxyPort.get(preferences)) }.getOrNull()
    }

    /** `null` turns the proxy off. */
    fun save(config: Config?) {
        val preferences = SharedPreferencesProvider.get()
        ClientSettings.proxyType.set(preferences, config?.type?.id)
        ClientSettings.proxyHost.set(preferences, config?.host)
        ClientSettings.proxyPort.set(preferences, config?.port ?: 0)
    }

    /** Idempotent; must run before the first network client is built. */
    @Synchronized
    fun install() {
        val existing = ProxySelector.getDefault()
        if (existing is Selector) return
        ProxySelector.setDefault(Selector(existing, ::current))
    }

    internal class Selector(
        private val fallback: ProxySelector?,
        private val config: () -> Config?,
    ) : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val proxy = config()
            if (proxy == null || uri.host.isNullOrEmpty() || isLoopback(uri.host)) {
                return fallback?.select(uri) ?: listOf(Proxy.NO_PROXY)
            }
            // Unresolved, so a SOCKS proxy does the name lookup too and local DNS never sees it.
            return listOf(Proxy(proxy.type.javaType, InetSocketAddress.createUnresolved(proxy.host, proxy.port)))
        }

        override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) {
            if (config() == null) fallback?.connectFailed(uri, address, error)
        }

        // String checks only: resolving the name here would itself be a direct DNS lookup.
        private fun isLoopback(host: String) = host.equals("localhost", ignoreCase = true) ||
            host.startsWith("127.") || host.removeSurrounding("[", "]") == "::1"
    }
}
