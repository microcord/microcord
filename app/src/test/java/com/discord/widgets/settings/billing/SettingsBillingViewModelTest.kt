package com.discord.widgets.settings.billing

import com.discord.models.domain.ModelPaymentSource
import com.discord.models.domain.ModelSubscription
import com.discord.models.domain.billing.ModelBillingAddress
import com.discord.stores.StorePaymentSources
import com.discord.stores.StoreSubscriptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBillingViewModelTest {
    private val address = ModelBillingAddress("", "", "", "", "", "US", "")
    private val first = card("first", true)
    private val second = card("second", false)

    @Test
    fun generatesReferencePaymentSourceRows() {
        val rows = SettingsBillingViewModel.generateListItems(
            listOf(first, second),
            subscription(paymentSourceId = first.id, status = ModelSubscription.Status.ACTIVE),
        )

        assertEquals(5, rows.size)
        assertEquals(
            PaymentSourceAdapter.PaymentSourceHeader.Type.DEFAULT,
            (rows[0] as PaymentSourceAdapter.PaymentSourceHeader).headerType,
        )
        assertTrue((rows[1] as PaymentSourceAdapter.PaymentSourceItem).isPremium)
        assertEquals(
            PaymentSourceAdapter.PaymentSourceHeader.Type.OTHER,
            (rows[2] as PaymentSourceAdapter.PaymentSourceHeader).headerType,
        )
        assertFalse((rows[3] as PaymentSourceAdapter.PaymentSourceItem).isPremium)
        assertTrue(rows[4] is PaymentSourceAdapter.PaymentSourceAddItem)
        assertTrue(SettingsBillingViewModel.generateListItems(emptyList(), null).isEmpty())
    }

    @Test
    fun canceledSubscriptionDoesNotLockPaymentSource() {
        val row = SettingsBillingViewModel.generateListItems(
            listOf(first),
            subscription(paymentSourceId = first.id, status = ModelSubscription.Status.CANCELED),
        )[1] as PaymentSourceAdapter.PaymentSourceItem

        assertFalse(row.isPremium)
    }

    @Test
    fun failureWinsOverLoading() {
        assertTrue(
            SettingsBillingViewModel.viewStateFor(
                SettingsBillingViewModel.StoreState(
                    StorePaymentSources.PaymentSourcesState.Loading,
                    StoreSubscriptions.SubscriptionsState.Failure,
                ),
            ) is SettingsBillingViewModel.ViewState.Failure,
        )
    }

    private fun card(id: String, default: Boolean) = ModelPaymentSource.ModelPaymentSourceCard(
        brand = "visa",
        last4 = "1234",
        expiresMonth = 1,
        expiresYear = 2030,
        id = id,
        invalid = false,
        billingAddress = address,
        default = default,
    )

    private fun subscription(paymentSourceId: String, status: ModelSubscription.Status) = ModelSubscription(
        id = "subscription",
        createdAt = "2021-01-01T00:00:00+00:00",
        canceledAt = null,
        currentPeriodStart = "2021-01-01T00:00:00+00:00",
        currentPeriodEnd = "2021-02-01T00:00:00+00:00",
        paymentSourceId = paymentSourceId,
        paymentGateway = null,
        trialId = null,
        trialEndsAt = null,
        renewalMutations = null,
        items = emptyList(),
        paymentGatewayPlanId = null,
        statusValue = status.intRepresentation,
        typeValue = 1,
    )
}
