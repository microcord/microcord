package com.discord.stores

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreGatewayConnectionPolicyTest {
    @Test
    fun headlessGateOnlyDefersTheFirstForegroundConnection() {
        assertTrue(shouldConnectGateway(disableHeadlessGateway = false, hasForegrounded = false))
        assertFalse(shouldConnectGateway(disableHeadlessGateway = true, hasForegrounded = false))
        assertTrue(shouldConnectGateway(disableHeadlessGateway = true, hasForegrounded = true))
    }
}
