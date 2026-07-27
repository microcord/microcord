package com.discord.stores

import com.discord.models.domain.ModelPaymentSource
import com.discord.models.domain.PaymentSourceRaw
import com.discord.models.domain.billing.ModelBillingAddress
import com.discord.stores.updates.ObservationDeck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rx.schedulers.TestScheduler

class StorePaymentSourcesTest {
    private val scheduler = TestScheduler()
    private val dispatcher = Dispatcher(scheduler)
    private val deck = ObservationDeck()
    private val store = StorePaymentSources(dispatcher, deck) { null }

    init {
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
    }

    @Test
    fun normalizesSourcesAndPublishesIsolatedSnapshots() {
        val prioritized = store.ensureDefaultPaymentSource(
            listOf(paypal("paypal", isDefault = false), card("card", isDefault = true)),
        )
        assertEquals(listOf("card", "paypal"), prioritized.map(ModelPaymentSource::id))

        val normalized = store.ensureDefaultPaymentSource(
            listOf(paypal("paypal", isDefault = false), card("card", isDefault = false)),
        )

        assertEquals(listOf("paypal", "card"), normalized.map(ModelPaymentSource::id))
        assertTrue(normalized.first().default)
        assertFalse(normalized.last().default)

        val mutable = normalized.toMutableList()
        dispatcher.schedule { store.handlePaymentSourcesFetchSuccess(mutable) }
        scheduler.triggerActions()
        mutable.clear()

        val loaded = store.getPaymentSourcesState() as StorePaymentSources.PaymentSourcesState.Loaded
        assertEquals(2, loaded.paymentSources.size)
    }

    @Test
    fun gatewayInvalidationRefetchesAndLogoutResetsState() {
        store.handleUserPaymentSourcesUpdate()
        scheduler.triggerActions()
        assertEquals(StorePaymentSources.PaymentSourcesState.Failure, store.getPaymentSourcesState())

        dispatcher.schedule { store.handlePreLogout() }
        scheduler.triggerActions()
        assertEquals(StorePaymentSources.PaymentSourcesState.Unfetched, store.getPaymentSourcesState())
    }

    @Test(expected = StorePaymentSources.UnableToWrapException::class)
    fun rejectsUnsupportedPaymentSourceTypes() {
        store.ensureDefaultPaymentSource(listOf(raw(type = 99, id = "unknown", isDefault = false)))
    }

    private fun card(id: String, isDefault: Boolean) = raw(
        type = 1,
        id = id,
        isDefault = isDefault,
        brand = "visa",
        last4 = "1234",
    )

    private fun paypal(id: String, isDefault: Boolean) = raw(
        type = 2,
        id = id,
        isDefault = isDefault,
        email = "test@example.com",
    )

    private fun raw(
        type: Int,
        id: String,
        isDefault: Boolean,
        email: String? = null,
        brand: String? = null,
        last4: String? = null,
    ) = PaymentSourceRaw(
        type = type,
        id = id,
        invalid = false,
        billingAddress = BILLING_ADDRESS,
        default = isDefault,
        email = email,
        brand = brand,
        last_4 = last4,
        expiresMonth = 1,
        expiresYear = 2030,
    )

    private companion object {
        val BILLING_ADDRESS = ModelBillingAddress("", "", "", "", "", "", "")
    }
}
