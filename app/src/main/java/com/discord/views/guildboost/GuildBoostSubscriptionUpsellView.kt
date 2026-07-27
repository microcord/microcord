package com.discord.views.guildboost

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.discord.R
import com.discord.api.premium.PremiumTier
import com.discord.utilities.color.ColorCompat
import java.text.NumberFormat
import java.util.Currency

class GuildBoostSubscriptionUpsellView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val subheading: TextView
    private val blurb: TextView
    private val price: TextView

    init {
        val content = LayoutInflater.from(context).inflate(R.layout.view_premium_upsell_info, this, false)
        addView(content)
        subheading = requireNotNull(content.findViewById(R.id.view_premium_upsell_info_subheading))
        blurb = requireNotNull(content.findViewById(R.id.view_premium_upsell_info_subheading_blurb))
        price = requireNotNull(content.findViewById(R.id.view_premium_upsell_info_subheading_price))
    }

    fun configure(userPremiumTier: PremiumTier, showBlurb: Boolean) {
        blurb.visibility = if (showBlurb) View.VISIBLE else View.GONE
        val originalPrice = formatUsd(BOOST_PRICE_CENTS)
        val discountedPrice = formatUsd((BOOST_PRICE_CENTS * (1 - BOOST_DISCOUNT)).toInt())
        val discount = NumberFormat.getPercentInstance().format(BOOST_DISCOUNT)
        val basePrice = styled(
            context.getString(R.string.guild_settings_premium_upsell_subheading)
                .replace("{price}", originalPrice),
        )
        val extraPrice = styled(
            context.getString(R.string.guild_settings_premium_upsell_subheading_extra_android)
                .replace("{price}", discountedPrice)
                .replace("{originalPrice}", originalPrice),
        )

        when (userPremiumTier) {
            PremiumTier.NONE, PremiumTier.PREMIUM_GUILD_SUBSCRIPTION_ONLY, PremiumTier.TIER_0 -> {
                subheading.text = basePrice
                price.visibility = View.GONE
            }
            PremiumTier.TIER_1 -> {
                subheading.text = context.getString(
                    R.string.guild_settings_premium_upsell_subheading_tier_1_mobile,
                ).replace("{discountPercent}", discount)
                price.text = extraPrice
                price.visibility = View.VISIBLE
            }
            PremiumTier.TIER_2 -> {
                subheading.text = context.getString(
                    R.string.guild_settings_premium_upsell_subheading_tier_2_mobile,
                ).replace("{freeSubscriptionCount}", FREE_BOOST_COUNT.toString())
                    .replace("{discountPercent}", discount)
                price.text = extraPrice
                price.visibility = View.VISIBLE
            }
        }
    }

    private fun formatUsd(cents: Int): String {
        val locale = resources.configuration.locale
        return NumberFormat.getCurrencyInstance(locale).apply {
            currency = Currency.getInstance("USD")
        }.format(cents / 100f)
    }

    private fun styled(source: String): CharSequence {
        val text = SpannableStringBuilder(
            source.replace("$[", "").replace("](originalPriceHook)", "").replace("!!", ""),
        )
        applyMarkup(text, Regex("\\*\\*(.+?)\\*\\*")) { StyleSpan(Typeface.BOLD) }
        applyMarkup(text, Regex("~~(.+?)~~")) {
            arrayOf(StrikethroughSpan(), ForegroundColorSpan(ColorCompat.getThemedColor(context, R.attr.colorHeaderSecondary)))
        }
        return text
    }

    private fun applyMarkup(text: SpannableStringBuilder, pattern: Regex, spans: () -> Any) {
        while (true) {
            val match = pattern.find(text) ?: return
            val value = match.groupValues[1]
            text.replace(match.range.first, match.range.last + 1, value)
            val span = spans()
            (if (span is Array<*>) span.filterNotNull() else listOf(span)).forEach {
                text.setSpan(
                    it,
                    match.range.first,
                    match.range.first + value.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }

    private companion object {
        const val BOOST_PRICE_CENTS = 499
        const val FREE_BOOST_COUNT = 2
        const val BOOST_DISCOUNT = 0.3
    }
}
