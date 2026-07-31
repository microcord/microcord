package com.discord.utilities.billing

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.DrawableRes
import com.discord.R
import com.discord.models.domain.ModelPaymentSource
import com.discord.utilities.locale.LocaleManager
import java.text.NumberFormat
import java.util.Calendar
import java.util.Currency
import java.util.Date

const val GRACE_PERIOD_LONG = 7
const val GRACE_PERIOD_SHORT = 3
const val MAX_ACCOUNT_HOLD_DAYS = 30

val GRANDFATHERED_MONTHLY_END_DATE: Date = Calendar.getInstance().apply { set(2020, 2, 1) }.time
val GRANDFATHERED_YEARLY_END_DATE: Date = Calendar.getInstance().apply { set(2021, 0, 1) }.time

fun getFormattedPriceUsd(priceUsCents: Int, context: Context): CharSequence =
    NumberFormat.getCurrencyInstance(LocaleManager().getPrimaryLocale(context)).apply {
        currency = Currency.getInstance("USD")
    }.format(priceUsCents / 100f)

@SuppressLint("DefaultLocale")
@DrawableRes
fun getPaymentSourceIcon(paymentSource: ModelPaymentSource): Int = when (paymentSource) {
    is ModelPaymentSource.ModelPaymentSourcePaypal -> R.drawable.ic_creditcard_paypal
    is ModelPaymentSource.ModelPaymentSourceCard -> when (paymentSource.brand.lowercase()) {
        "amex", "american-express" -> R.drawable.ic_creditcard_amex
        "discover" -> R.drawable.ic_creditcard_discover
        "mastercard", "master-card" -> R.drawable.ic_creditcard_mastercard
        "visa" -> R.drawable.ic_creditcard_visa
        else -> R.drawable.ic_creditcard_generic
    }
}
