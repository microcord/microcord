package com.discord.widgets.settings

import com.discord.api.premium.PremiumTier
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.widgets.settings.guildboost.SettingsGuildBoostViewModel
import com.discord.widgets.settings.premium.ClaimOutboundPromoViewModel
import com.discord.widgets.settings.premium.ClaimStatus
import com.discord.widgets.settings.premium.SettingsGiftingViewModel
import com.discord.widgets.settings.premium.WidgetSettingsGiftingAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemainingSettingsContractsTest {
    @Test
    fun claimedPromoPublishesCopyRedeemAndDismissEvents() {
        val status = ClaimStatus.Claimed(7L, "private-code", "body", "https://example.com/redeem", UtcDateTime(42L))
        val viewModel = ClaimOutboundPromoViewModel(status)
        val events = mutableListOf<ClaimOutboundPromoViewModel.Event>()
        viewModel.observeEvents().subscribe(events::add)

        viewModel.copyClicked()
        viewModel.redeemClicked()
        viewModel.maybeLaterClicked()

        assertEquals(
            listOf(
                ClaimOutboundPromoViewModel.Event.CopyCode("private-code"),
                ClaimOutboundPromoViewModel.Event.OpenRedemptionUrl("https://example.com/redeem"),
                ClaimOutboundPromoViewModel.Event.Dismiss,
            ),
            events,
        )
        assertEquals(ClaimOutboundPromoViewModel.ViewState.Claimed(status), viewModel.viewState)
    }

    @Test
    fun giftingItemsUseReferenceStableKeys() {
        assertEquals("", WidgetSettingsGiftingAdapter.GiftItem(WidgetSettingsGiftingAdapter.GiftItem.TYPE_NO_GIFTS).key)
        assertEquals("null", WidgetSettingsGiftingAdapter.GiftItem(WidgetSettingsGiftingAdapter.GiftItem.TYPE_SKU).key)
        assertEquals("null", WidgetSettingsGiftingAdapter.GiftItem(WidgetSettingsGiftingAdapter.GiftItem.TYPE_GIFT).key)
    }

    @Test
    fun remainingStateContractsPreserveNullableTransientFields() {
        val gifting = SettingsGiftingViewModel.ViewState.Loaded(
            myEntitlements = emptyMap(),
            resolvingGiftState = SettingsGiftingViewModel.ResolvingGiftState.NotResolving,
            expandedSkuOrPlanIds = emptySet(),
            outboundPromos = emptyList(),
            myPurchasedGifts = emptyMap(),
            lastCopiedCode = null,
        )
        val boost = SettingsGuildBoostViewModel.ViewState.Loaded(
            canCancelBoosts = false,
            canUncancelBoosts = false,
            guildBoostItems = emptyList(),
            sampleGuildItems = emptyList(),
            pendingAction = null,
            userPremiumTier = PremiumTier.NONE,
        )

        assertNull(gifting.lastCopiedCode)
        assertNull(boost.pendingAction)
    }
}
