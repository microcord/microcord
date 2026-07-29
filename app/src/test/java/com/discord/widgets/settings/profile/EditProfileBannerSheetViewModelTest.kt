package com.discord.widgets.settings.profile

import com.discord.api.premium.PremiumTier
import com.discord.api.user.NsfwAllowance
import com.discord.api.user.UserProfile
import com.discord.models.user.MeUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditProfileBannerSheetViewModelTest {
    @Test fun `initial color wins over default and survives store refresh`() {
        val state = EditProfileBannerSheetViewModel.nextViewState(
            current = null,
            storeState = storeState(PremiumTier.NONE),
            initialColorPreviewHex = "#123456",
            defaultBannerColorHex = "#abcdef",
        )
        assertEquals("#123456", state.bannerColorHex)

        assertEquals(
            "#654321",
            EditProfileBannerSheetViewModel.nextViewState(
                state.copy(bannerColorHex = "#654321"),
                storeState(PremiumTier.NONE),
                "#123456",
                "#abcdef",
            ).bannerColorHex,
        )
    }

    @Test fun `only tier two users bypass the banner image upsell`() {
        assertTrue(next(PremiumTier.NONE).showPremiumUpsell)
        assertFalse(next(PremiumTier.TIER_2).showPremiumUpsell)
    }

    private fun next(tier: PremiumTier) = EditProfileBannerSheetViewModel.nextViewState(
        null,
        storeState(tier),
        null,
        "#abcdef",
    )

    private fun storeState(tier: PremiumTier) = EditProfileBannerSheetViewModel.StoreState(
        MeUser(
            1, "user", null, null, false, false, 0, tier, null, false, true,
            null, 0, 0, null, NsfwAllowance.UNKNOWN, null, null,
        ),
        UserProfile(),
    )
}
