package com.discord.stores

import com.discord.models.domain.ModelInvite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoreInviteSettingsTest {
    @Test
    fun inviteCodeAndSettingsPublishCurrentValues() {
        val store = StoreInviteSettings()

        assertNull(store.getInviteCode().toBlocking().first())
        store.setInviteCode("abc", StoreInviteSettings.LOCATION_DEEPLINK, 42L)
        assertEquals(
            StoreInviteSettings.InviteCode("abc", StoreInviteSettings.LOCATION_DEEPLINK, 42L),
            store.getInviteCode().toBlocking().first(),
        )
        store.clearInviteCode()
        assertNull(store.getInviteCode().toBlocking().first())

        val settings = ModelInvite.Settings(ModelInvite.Settings.HALF_HOUR, 10, true)
        store.setInviteSettings(settings)
        assertEquals(settings, store.getInviteSettings().toBlocking().first())
    }

    @Test
    fun guildSelectionResetsToTheDefaultExpiration() {
        val store = StoreInviteSettings()

        store.setInviteSettings(ModelInvite.Settings(ModelInvite.Settings.HALF_HOUR))
        store.handleGuildSelected(123L)

        assertEquals(ModelInvite.Settings.SEVEN_DAYS, store.getInviteSettings().toBlocking().first().maxAge)
    }
}
