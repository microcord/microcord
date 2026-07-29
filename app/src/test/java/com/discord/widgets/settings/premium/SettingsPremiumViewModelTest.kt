package com.discord.widgets.settings.premium

import com.discord.stores.StoreEntitlements
import com.discord.stores.StoreGooglePlayPurchases
import com.discord.stores.StoreGooglePlaySkuDetails
import com.discord.stores.StoreGuildBoost
import com.discord.stores.StorePaymentSources
import com.discord.stores.StoreSubscriptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPremiumViewModelTest {
    @Test
    fun `all loaded sources produce content even without a subscription`() {
        val state = loadedState()

        val result = SettingsPremiumViewModel.viewStateFor(state)

        assertTrue(result is SettingsPremiumViewModel.ViewState.Loaded)
        result as SettingsPremiumViewModel.ViewState.Loaded
        assertEquals(null, result.premiumSubscription)
        assertEquals(3, result.pastDueGracePeriodDays)
        assertTrue(result.paymentSources.isEmpty())
        assertTrue(result.purchases.isEmpty())
    }

    @Test
    fun `renewal invoice failure owns the screen failure state`() {
        val result = SettingsPremiumViewModel.viewStateFor(
            loadedState().copy(
                renewalInvoicePreviewFetch = SettingsPremiumViewModel.InvoicePreviewFetch.Error,
            ),
        )

        assertEquals(SettingsPremiumViewModel.ViewState.Failure, result)
    }

    @Test
    fun `entitlement failure remains loading like 126 21`() {
        val result = SettingsPremiumViewModel.viewStateFor(
            loadedState().copy(entitlementState = StoreEntitlements.State.Failure),
        )

        assertEquals(SettingsPremiumViewModel.ViewState.Loading, result)
    }

    private fun loadedState() = SettingsPremiumViewModel.StoreState(
        paymentSourcesState = StorePaymentSources.PaymentSourcesState.Loaded(emptyList()),
        subscriptionsState = StoreSubscriptions.SubscriptionsState.Loaded(emptyList()),
        entitlementState = StoreEntitlements.State.Loaded(emptyMap(), emptyMap()),
        guildBoostState = StoreGuildBoost.State.Loaded(emptyMap()),
        currentInvoicePreviewFetch = SettingsPremiumViewModel.InvoicePreviewFetch.Invoice(null),
        renewalInvoicePreviewFetch = SettingsPremiumViewModel.InvoicePreviewFetch.Invoice(null),
        skuDetailsState = StoreGooglePlaySkuDetails.State.Loaded(emptyMap()),
        purchaseState = StoreGooglePlayPurchases.State.Loaded(),
    )
}
