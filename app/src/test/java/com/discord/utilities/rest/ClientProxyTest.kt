package com.discord.utilities.rest

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Test

class ClientProxyTest {
    @Test fun aConfiguredProxyIsTheOnlyRouteAndLeavesNameLookupToIt() {
        val selector = ClientProxy.Selector(null) { ClientProxy.Config(ClientProxy.Type.SOCKS, "10.0.0.2", 1080) }

        val route = selector.select(URI("wss://gateway.discord.gg/?v=9")).single()

        assertEquals(Proxy.Type.SOCKS, route.type())
        val address = route.address() as InetSocketAddress
        assertEquals("10.0.0.2", address.hostString)
        assertEquals(1080, address.port)
        assertEquals(true, address.isUnresolved)
    }

    @Test fun loopbackAndAnUnsetProxyGoDirect() {
        val proxied = ClientProxy.Selector(null) { ClientProxy.Config(ClientProxy.Type.HTTP, "proxy", 8080) }
        val unset = ClientProxy.Selector(null) { null }

        assertEquals(listOf(Proxy.NO_PROXY), proxied.select(URI("http://127.0.0.1:8097/")))
        assertEquals(listOf(Proxy.NO_PROXY), proxied.select(URI("http://localhost/")))
        assertEquals(listOf(Proxy.NO_PROXY), unset.select(URI("https://discord.com/api/")))
    }
}
