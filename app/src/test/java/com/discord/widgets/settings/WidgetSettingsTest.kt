package com.discord.widgets.settings

import com.discord.api.premium.PremiumTier
import com.discord.api.user.NsfwAllowance
import com.discord.models.domain.Consent
import com.discord.models.domain.Consents
import com.discord.models.user.MeUser
import com.discord.stores.StoreUserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSettingsTest {
    @Test fun `display name prefers global name and ignores human discriminator`() {
        assertEquals("Manti", WidgetSettings.displayName(user(42, "Manti")))
        assertEquals("mantikafasi", WidgetSettings.displayName(user(0)))
    }

    /**
     * `WidgetSettings.hideUnavailableRows` shows only the rows in `implementedRouteIds`, so this set
     * is the contract that the settings screen has no dead taps. A row belongs here only once its
     * destination fragment exists and is reachable:
     *
     *  - `notifications` -> `WidgetSettingsNotifications`, which renders the recovered layout from
     *    `NotificationSettingsV2`. Its toggles are deliberately disabled until `StoreStream`
     *    publishes `StoreNotifications`; the route itself is live.
     *  - `text_images_settings`, `accessibility`, `behavior`, `activity_status` all read and write
     *    `StoreStream.getUserSettings()`.
     *  - `appearance` -> `WidgetSettingsAppearance` and `language` -> `WidgetSettingsLanguage`, both
     *    reading `StoreStream.getUserSettingsSystem()` and mirroring the change to the account
     *    through `RestAPI`.
     *  - `privacy` and `voice` are backed by their recovered settings fragments.
     */
    @Test fun `main settings exposes only implemented recovered app routes`() {
        assertEquals(setOf(
            com.discord.R.id.account,
            com.discord.R.id.profile,
            com.discord.R.id.authorized_apps,
            com.discord.R.id.text_images_settings,
            com.discord.R.id.appearance,
            com.discord.R.id.language,
            com.discord.R.id.accessibility,
            com.discord.R.id.behavior,
            com.discord.R.id.activity_status,
            com.discord.R.id.notifications,
            com.discord.R.id.privacy,
            com.discord.R.id.voice,
            com.discord.R.id.developer_options,
        ), WidgetSettings.implementedRouteIds)
    }

    @Test fun `privacy terminal failure leaves loading and exposes the exact error`() {
        val error = IllegalStateException("consent unavailable")
        val state = SettingsPrivacyViewModel.toViewState(
            privacyState(StoreUserSettings.ConsentState.Failed(null, error)),
        )

        assertTrue(state is SettingsPrivacyViewModel.ViewState.Loaded)
        state as SettingsPrivacyViewModel.ViewState.Loaded
        assertNull(state.consents)
        assertSame(error, state.consentError)
    }

    @Test fun `privacy loading and cached terminal values drive loading exactly`() {
        val loading = SettingsPrivacyViewModel.toViewState(
            privacyState(StoreUserSettings.ConsentState.Loading(null)),
        ) as SettingsPrivacyViewModel.ViewState.Loaded
        assertNull(loading.consents)
        assertNull(loading.consentError)
        val consents = Consents(Consent(true), Consent(false))
        val loaded = SettingsPrivacyViewModel.toViewState(
            privacyState(StoreUserSettings.ConsentState.Failed(consents, IllegalStateException("offline"))),
        ) as SettingsPrivacyViewModel.ViewState.Loaded
        assertEquals(consents, loaded.consents)
    }

    private fun user(discriminator: Int, globalName: String? = null) = MeUser(
        1, "mantikafasi", null, null, false, false, discriminator, PremiumTier.NONE,
        null, false, true, null, 0, 0, null, NsfwAllowance.UNKNOWN, null, null, globalName,
    )

    private fun privacyState(consentState: StoreUserSettings.ConsentState) =
        SettingsPrivacyViewModel.StoreState(
            me = user(0),
            explicitContentFilter = 2,
            defaultRestrictedGuilds = true,
            friendSourceFlags = null,
            contactSyncConnection = null,
            showContactSync = false,
            userDiscoveryFlags = 0,
            consentState = consentState,
            harvestState = null,
        )
}
