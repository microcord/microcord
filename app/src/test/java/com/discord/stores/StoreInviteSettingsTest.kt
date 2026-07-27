package com.discord.stores

import com.discord.models.domain.ModelInvite
import com.discord.models.experiments.domain.Experiment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoreInviteSettingsTest {
    @Test
    fun inviteCodeAndSettingsPublishCurrentValues() {
        val store = StoreInviteSettings { _, _, _ -> null }

        assertEquals(ModelInvite.Settings.ONE_DAY, store.getInviteSettings(1L).maxAge)
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
    fun guildSelectionUsesExperimentBucketAndForwardsExposureFlag() {
        var request: Triple<String, Long, Boolean>? = null
        val store = StoreInviteSettings { name, guildId, trackExposure ->
            request = Triple(name, guildId, trackExposure)
            Experiment(1, 1, 0, false) {}
        }

        store.handleGuildSelected(123L)

        assertEquals(ModelInvite.Settings.SEVEN_DAYS, store.getInviteSettings().toBlocking().first().maxAge)
        store.getInviteGuildExperiment(123L, true)
        assertEquals(
            Triple("2021-03_android_extend_invite_expiration", 123L, true),
            request,
        )
    }
}
