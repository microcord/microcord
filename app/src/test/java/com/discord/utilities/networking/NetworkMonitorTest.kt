package com.discord.utilities.networking

import android.net.NetworkCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkMonitorTest {
    @Test
    fun networkCapabilitiesAndAirplaneModeMatchTheReferenceProbe() {
        assertFalse(isConnectedNetwork(false) { false })
        assertTrue(isConnectedNetwork(true) { false })
        for (transport in listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_VPN)) {
            assertTrue(isConnectedNetwork(false) { it == transport })
            // Missing default capabilities must not discard a usable fallback interface.
            assertTrue(listOf(emptySet(), setOf(transport)).any { isConnectedNetwork(false, it::contains) })
        }
        assertFalse(isConnectedNetwork(false) { it == NetworkCapabilities.TRANSPORT_BLUETOOTH })
        assertEquals(NetworkMonitor.State.ONLINE, resolveNetworkState(connected = true, airplaneMode = true))
        assertEquals(NetworkMonitor.State.ONLINE, resolveNetworkState(connected = true, airplaneMode = false))
        assertEquals(NetworkMonitor.State.OFFLINE_AIRPLANE_MODE, resolveNetworkState(connected = false, airplaneMode = true))
        assertEquals(NetworkMonitor.State.OFFLINE, resolveNetworkState(connected = false, airplaneMode = false))
    }
}
