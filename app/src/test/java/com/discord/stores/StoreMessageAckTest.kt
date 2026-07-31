package com.discord.stores

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreMessageAckTest {
    @Test
    fun `auto ack requires loaded attached bottom state`() {
        assertTrue(StoreMessageAck.shouldAutoAck(true, true, false, false, false))
        assertFalse(StoreMessageAck.shouldAutoAck(false, true, false, false, false))
        assertFalse(StoreMessageAck.shouldAutoAck(true, false, false, false, false))
        assertFalse(StoreMessageAck.shouldAutoAck(true, true, true, false, false))
        assertFalse(StoreMessageAck.shouldAutoAck(true, true, false, true, false))
        assertFalse(StoreMessageAck.shouldAutoAck(true, true, false, false, true))
    }

}
