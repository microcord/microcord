package com.discord.widgets.settings.billing

import com.discord.R
import com.discord.models.domain.ModelPaymentSource
import com.discord.models.domain.billing.ModelBillingAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class PaymentSourceViewTest {
    private val address = ModelBillingAddress("", "", "", "", "", "", "")

    @Test
    fun mapsPaymentSourceIcons() {
        fun card(brand: String) = ModelPaymentSource.ModelPaymentSourceCard(
            brand, "1234", 1, 2030, "id", false, address, false,
        )

        assertEquals(R.drawable.ic_creditcard_amex, PaymentSourceView.paymentSourceIcon(card("american-express")))
        assertEquals(R.drawable.ic_creditcard_mastercard, PaymentSourceView.paymentSourceIcon(card("Master-Card")))
        assertEquals(R.drawable.ic_creditcard_visa, PaymentSourceView.paymentSourceIcon(card("VISA")))
        assertEquals(R.drawable.ic_creditcard_generic, PaymentSourceView.paymentSourceIcon(card("other")))
        assertEquals(
            R.drawable.ic_creditcard_paypal,
            PaymentSourceView.paymentSourceIcon(
                ModelPaymentSource.ModelPaymentSourcePaypal("a@example.com", "id", false, address, false),
            ),
        )
    }
}
