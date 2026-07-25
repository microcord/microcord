package com.discord.widgets.settings

import com.discord.api.premium.PremiumTier
import com.discord.api.user.NsfwAllowance
import com.discord.models.user.MeUser
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSettingsTest {
    @Test fun `display name preserves legacy discriminator and modern username`() {
        assertEquals("mantikafasi#0042", WidgetSettings.displayName(user(42)))
        assertEquals("mantikafasi", WidgetSettings.displayName(user(0)))
    }

    @Test fun `main settings exposes only implemented recovered app routes`() {
        assertEquals(setOf(
            com.discord.R.id.text_images_settings,
            com.discord.R.id.accessibility,
            com.discord.R.id.behavior,
            com.discord.R.id.activity_status,
        ), WidgetSettings.implementedRouteIds)
    }

    private fun user(discriminator: Int) = MeUser(
        1, "mantikafasi", null, null, false, false, discriminator, PremiumTier.NONE,
        null, false, true, null, 0, 0, null, NsfwAllowance.UNKNOWN, null, null,
    )
}
