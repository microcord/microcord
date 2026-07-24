package com.discord.gateway

import com.discord.stores.StoreStream
import rx.Subscription

class GatewayEventRouter(private val stores: StoreStream) : GatewayEventHandler {
    private var guildSubscription: Subscription? = null

    fun bind(socket: GatewaySocket) {
        guildSubscription?.unsubscribe()
        guildSubscription = StoreStream.getGuildSubscriptions().observeSubscriptions()
            .subscribe(socket::updateGuildSubscriptions)
    }

    override fun handleConnected(connected: Boolean) = stores.handleGatewayConnected(connected)
    override fun handleConnectionReady(connectionReady: Boolean) = stores.handleGatewayConnectionReady(connectionReady)
    override fun handleDisconnect(authenticationFailed: Boolean) {
        if (authenticationFailed) stores.handleGatewayAuthenticationFailure()
    }
    override fun handleDispatch(type: String?, data: Any) = stores.handleGatewayDispatch(type, data)
}
