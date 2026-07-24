package com.discord.gateway

import com.discord.stores.StoreStream

class GatewayEventRouter(private val stores: StoreStream) : GatewayEventHandler {
    override fun handleConnected(connected: Boolean) = stores.handleGatewayConnected(connected)
    override fun handleConnectionReady(connectionReady: Boolean) = Unit
    override fun handleDisconnect(authenticationFailed: Boolean) {
        if (authenticationFailed) stores.handleGatewayAuthenticationFailure()
    }
    override fun handleDispatch(type: String?, data: Any) = stores.handleGatewayDispatch(type, data)
}
