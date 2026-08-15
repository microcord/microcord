package com.discord.gateway

import android.content.SharedPreferences
import android.util.Log
import com.discord.models.domain.ModelGateway
import com.discord.stores.StoreAuthentication
import com.discord.stores.StoreClientDataState
import com.discord.stores.authentication.AuthStateCache
import com.discord.utilities.analytics.AnalyticSuperProperties
import com.discord.utilities.logging.AppGatewaySocketLogger
import com.discord.utilities.websocket.GatewayCompression
import com.discord.utilities.websocket.OkHttpGatewayWebSocketFactory

/** Starts one identified Gateway socket before the store graph finishes constructing. */
object FastConnectBootstrap {
    @Volatile private var identifyDataProvider: () -> IdentifyData? = { null }
    @Volatile private var identifyProperties: () -> Map<String, Any?> = { emptyMap() }
    private var socket: GatewaySocket? = null
    private val deferredHandler = DeferredGatewayEventHandler()

    @Synchronized
    fun start(preferences: SharedPreferences, sessionDurablePreferences: SharedPreferences) {
        if (socket != null) return
        val token = AuthStateCache(preferences).getCachedAuthState()?.token?.takeIf(String::isNotBlank) ?: return
        val clientState = runCatching {
            (StoreClientDataState.readFastConnectState(preferences) ?: StoreClientDataState.ClientDataState())
                .toIdentifyData()
        }.onFailure { error ->
            Log.w("ManticordGateway", "client state cache read failed: ${error.javaClass.simpleName}: ${error.message}")
        }.getOrNull()
        val installation = sessionDurablePreferences
            .getString(StoreAuthentication.CACHE_KEY_INSTALLATION, null)
            ?.takeIf(String::isNotBlank)
        val initialIdentify = IdentifyData(token, clientState)
        val initialProperties = AnalyticSuperProperties.superProperties +
            mapOf(
                "client_app_state" to "focused",
                "is_fast_connect" to true,
                "gateway_connect_reasons" to "cold_start",
            ) + if (installation != null) mapOf("installation_id" to installation) else emptyMap()
        identifyDataProvider = { initialIdentify }
        identifyProperties = { initialProperties }
        socket = GatewaySocket(
            identifyDataProvider = { identifyDataProvider() },
            identifyProperties = { identifyProperties() },
            eventHandler = deferredHandler,
            scheduler = RealGatewayScheduler(),
            discovery = GatewayDiscovery(preferences) { ModelGateway(DEFAULT_GATEWAY_URL) },
            webSocketFactory = OkHttpGatewayWebSocketFactory(),
            gatewaySocketLogger = AppGatewaySocketLogger.INSTANCE,
            initialCompression = GatewayCompression.ZSTD,
            identifyOnOpen = true,
        ).also(GatewaySocket::connect)
    }

    @Synchronized
    fun adopt(
        identifyDataProvider: () -> IdentifyData?,
        identifyProperties: () -> Map<String, Any?>,
    ): GatewaySocket? {
        this.identifyDataProvider = identifyDataProvider
        this.identifyProperties = identifyProperties
        return socket
    }

    fun attach(eventHandler: GatewayEventHandler) {
        deferredHandler.attach(eventHandler)
        socket?.completeFastConnect()
    }

    internal const val DEFAULT_GATEWAY_URL = "wss://gateway.discord.gg"
}

internal class DeferredGatewayEventHandler : GatewayEventHandler {
    private val pending = ArrayDeque<(GatewayEventHandler) -> Unit>()
    private var delegate: GatewayEventHandler? = null

    @Synchronized
    fun attach(eventHandler: GatewayEventHandler) {
        check(delegate == null) { "Fast Connect handler already attached" }
        pending.forEach { it(eventHandler) }
        pending.clear()
        delegate = eventHandler
    }

    @Synchronized
    private fun deliver(action: (GatewayEventHandler) -> Unit) {
        delegate?.let(action) ?: pending.addLast(action)
    }

    override fun handleConnected(connected: Boolean) = deliver { it.handleConnected(connected) }
    override fun handleConnectionReady(connectionReady: Boolean) = deliver { it.handleConnectionReady(connectionReady) }
    override fun handleFreshSession() = deliver(GatewayEventHandler::handleFreshSession)
    override fun handleDisconnect(authenticationFailed: Boolean) = deliver { it.handleDisconnect(authenticationFailed) }
    override fun handleDispatch(type: String?, data: Any) = deliver { it.handleDispatch(type, data) }
}
