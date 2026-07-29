package com.discord.widgets.settings.billing

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R
import com.discord.models.domain.ModelPaymentSource
import com.discord.utilities.time.TimeUtils
import java.util.Locale

class PaymentSourceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {
    private val icon: ImageView
    private val invalid: TextView
    private val premium: ImageView
    private val subtext: TextView
    private val title: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.payment_source_view, this)
        icon = requireNotNull(findViewById(R.id.payment_source_view_icon))
        invalid = requireNotNull(findViewById(R.id.payment_source_view_invalid))
        premium = requireNotNull(findViewById(R.id.payment_source_view_premium))
        subtext = requireNotNull(findViewById(R.id.payment_source_view_subtext))
        title = requireNotNull(findViewById(R.id.payment_source_view_title))
    }

    fun bind(paymentSource: ModelPaymentSource, isPremium: Boolean) {
        icon.setImageResource(paymentSourceIcon(paymentSource))
        invalid.visibility = if (paymentSource.invalid) View.VISIBLE else View.GONE
        premium.visibility = if (isPremium) View.VISIBLE else View.GONE

        when (paymentSource) {
            is ModelPaymentSource.ModelPaymentSourcePaypal -> {
                title.setText(R.string.payment_source_paypal)
                subtext.text = paymentSource.email
            }
            is ModelPaymentSource.ModelPaymentSourceCard -> {
                title.text = cardTitle(paymentSource.brand, paymentSource.last4)
                val month = TimeUtils.getLocalizedMonthName(paymentSource.expiresMonth, context)
                subtext.text = context.getString(R.string.payment_source_card_expires)
                    .replace("{month}", month)
                    .replace("{year}", paymentSource.expiresYear.toString())
            }
        }
    }

    private fun cardTitle(brand: String, last4: String): CharSequence {
        val text = context.getString(R.string.payment_source_card_ending)
            .replace("{brand}", brand)
            .replace("**{last_4}**", last4)
        return SpannableStringBuilder(text).apply {
            val start = text.lastIndexOf(last4)
            if (start >= 0) setSpan(
                StyleSpan(android.graphics.Typeface.BOLD),
                start,
                start + last4.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    companion object {
        @DrawableRes
        fun paymentSourceIcon(paymentSource: ModelPaymentSource): Int = when (paymentSource) {
            is ModelPaymentSource.ModelPaymentSourcePaypal -> R.drawable.ic_creditcard_paypal
            is ModelPaymentSource.ModelPaymentSourceCard -> when (paymentSource.brand.lowercase(Locale.ROOT)) {
                "amex", "american-express" -> R.drawable.ic_creditcard_amex
                "discover" -> R.drawable.ic_creditcard_discover
                "mastercard", "master-card" -> R.drawable.ic_creditcard_mastercard
                "visa" -> R.drawable.ic_creditcard_visa
                else -> R.drawable.ic_creditcard_generic
            }
        }
    }
}
