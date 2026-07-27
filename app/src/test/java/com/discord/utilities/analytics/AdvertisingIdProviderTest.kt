package com.discord.utilities.analytics

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvertisingIdProviderTest {
    @Test
    fun publishesOnlyAnAllowedRealisticIdentifier() {
        assertFalse(AdvertisingIdProvider.publish(null, limitAdTracking = false))
        assertFalse(
            AdvertisingIdProvider.publish(
                "00000000-0000-0000-0000-000000000000",
                limitAdTracking = false,
            ),
        )
        assertFalse(AdvertisingIdProvider.publish(UUID.randomUUID().toString(), limitAdTracking = true))

        val id = UUID.randomUUID().toString()
        assertTrue(AdvertisingIdProvider.publish(id, limitAdTracking = false))
        assertEquals(id, AnalyticSuperProperties.superProperties["device_advertiser_id"])
    }
}
