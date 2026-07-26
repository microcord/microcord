package com.discord.widgets.stickers

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.api.premium.PremiumTier
import com.discord.i18n.i18nFormat
import com.discord.i18n.i18nPlural
import com.discord.models.sticker.dto.ModelStickerPack
import com.discord.models.sticker.dto.ModelStickerPackStoreListing
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.locale.LocaleManager
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.time.TimeUtils
import java.text.NumberFormat
import java.util.Currency

/**
 * Price and expiry copy for a sticker pack.
 *
 * This is a port of the pricing half of the reference's `utilities/stickers/StickerUtils` and of
 * `utilities/billing/PremiumUtilsKt.getFormattedPriceUsd`. It lives here rather than in
 * `utilities/stickers/StickerUtils` only because that file is concurrently owned by the
 * sticker-animation work; **fold it into `StickerUtils` (and the formatter into
 * `utilities/billing`) once that lands** — nothing here is sticker-popout specific.
 *
 * The reference's `PremiumUtils.getSkuPrice` (Google Play billing) does not exist in this project,
 * so the tier-2 fallback price uses the same hard-coded cent amounts the reference uses for the
 * *pack* price, which is what [stickerPackPrice] encodes.
 */
internal object StickerPackFormatting {

    /** Cents. A Nitro subscriber pays less for a pack than a Nitro-Classic/boost-only user. */
    private const val PACK_PRICE_CENTS = 299
    private const val PACK_PRICE_CENTS_TIER_2 = 199

    /** Pack banners are uploaded as store assets of Discord's own "stickers" application. */
    private const val STICKER_APPLICATION_ID = 710982414301790216L

    /** Port of the reference's `StickerUtils.getBannerCDNAssetUrl`. */
    @JvmOverloads
    fun getBannerCDNAssetUrl(stickerPack: ModelStickerPack, size: Int? = null): String {
        val base = "${IconUtils.CDN_HOST}/app-assets/$STICKER_APPLICATION_ID/store/" +
            "${stickerPack.bannerAssetId}.${IconUtils.staticImageExtension}"
        return if (size != null) "$base?size=${IconUtils.getMediaProxySize(size)}" else base
    }

    private fun stickerPackPrice(premiumTier: PremiumTier): Int =
        if (premiumTier == PremiumTier.TIER_2) PACK_PRICE_CENTS_TIER_2 else PACK_PRICE_CENTS

    /** The "{discount}% off with Nitro" number the popout advertises. Currently 33. */
    fun calculatePremiumStickerPackDiscount(): Int {
        val base = stickerPackPrice(PremiumTier.PREMIUM_GUILD_SUBSCRIPTION_ONLY)
        return ((base - stickerPackPrice(PremiumTier.TIER_2)) * 100f / base).toInt()
    }

    private fun stickerPackPriceForPremiumTier(
        stickerPack: ModelStickerPack,
        premiumTier: PremiumTier,
    ): Int? {
        val price = stickerPack.storeListing?.sku?.price ?: return null
        return price.premium?.get(premiumTier)?.amount ?: price.amount
    }

    fun isStickerPackFreeForPremiumTier(
        stickerPack: ModelStickerPack,
        premiumTier: PremiumTier,
    ): Boolean =
        (premiumTier.grantsAccessToPremiumStickers() && stickerPack.isPremiumPack()) ||
            stickerPackPriceForPremiumTier(stickerPack, premiumTier) == 0

    private fun isPremiumTierAtLeast(premiumTier: PremiumTier, minimum: PremiumTier): Boolean =
        premiumTier.ordinal >= minimum.ordinal

    /** Cents rendered in the device's locale but always as USD, exactly as the reference does. */
    fun formattedPriceUsd(priceUsCents: Int, context: Context): String =
        NumberFormat.getCurrencyInstance(LocaleManager().getPrimaryLocale(context)).apply {
            currency = Currency.getInstance("USD")
        }.format(priceUsCents / 100f)

    /**
     * The label on the popout's buy button.
     *
     * Order matters: an owned-but-unusable pack advertises Nitro first, then a genuine discount for
     * the user's tier wins over the plain "free with tier N" copy.
     */
    fun getStickerPackPremiumPriceLabel(
        context: Context,
        stickerPack: ModelStickerPack,
        currentPremiumTier: PremiumTier,
        isPackEnabled: Boolean,
    ): CharSequence {
        if (isPackEnabled && !currentPremiumTier.grantsAccessToPremiumStickers()) {
            return context.getString(R.string.sticker_pack_premium_cta)
        }
        if (currentPremiumTier != PremiumTier.PREMIUM_GUILD_SUBSCRIPTION_ONLY) {
            val listPrice =
                stickerPackPriceForPremiumTier(stickerPack, PremiumTier.PREMIUM_GUILD_SUBSCRIPTION_ONLY)
            val yourPrice = stickerPackPriceForPremiumTier(stickerPack, currentPremiumTier)
            if (listPrice != null && yourPrice != null && listPrice != yourPrice) {
                val struck = strikethrough(context, formattedPriceUsd(listPrice, context))
                return if (yourPrice == 0) {
                    concat(context.getString(R.string.sticker_pack_price_free), " ", struck)
                } else {
                    concat(formattedPriceUsd(yourPrice, context), " ", struck)
                }
            }
        }
        if (isStickerPackFreeForPremiumTier(stickerPack, currentPremiumTier)) {
            return context.getString(R.string.sticker_pack_price_free)
        }
        if (isStickerPackFreeForPremiumTier(stickerPack, PremiumTier.TIER_1) &&
            !isPremiumTierAtLeast(currentPremiumTier, PremiumTier.TIER_1)
        ) {
            return context.getString(R.string.sticker_pack_price_free_with_premium_tier_1)
        }
        if (isStickerPackFreeForPremiumTier(stickerPack, PremiumTier.TIER_2) &&
            !isPremiumTierAtLeast(currentPremiumTier, PremiumTier.TIER_2)
        ) {
            return context.getString(R.string.sticker_pack_price_free_with_premium_tier_2)
        }
        if (isStickerPackFreeForPremiumTier(stickerPack, PremiumTier.TIER_0) &&
            !isPremiumTierAtLeast(currentPremiumTier, PremiumTier.TIER_0) &&
            currentPremiumTier != PremiumTier.TIER_1
        ) {
            return context.getString(R.string.sticker_pack_price_free_with_premium_tier_2)
        }
        val tier2Price = formattedPriceUsd(stickerPackPrice(PremiumTier.TIER_2), context)
        return if (currentPremiumTier.grantsAccessToPremiumStickers()) {
            tier2Price
        } else {
            context.i18nFormat(R.string.sticker_picker_price_with_premium_tier_2, "price" to tier2Price)
        }
    }

    /**
     * "Limited time left: 3 days" for a pack that will stop being sold, or `""` for one that will
     * not. Under a day it counts down in `hh:mm:ss`; a listing that has already lapsed reads
     * "Expiring Soon" rather than a negative duration.
     */
    fun getLimitedTimeLeftString(
        context: Context,
        stickerPackStoreListing: ModelStickerPackStoreListing?,
        clock: Clock = ClockFactory.get(),
    ): CharSequence {
        if (stickerPackStoreListing?.unpublishedAt == null) return ""
        val remaining = stickerPackStoreListing.unpublishedAtDate - clock.currentTimeMillis()
        val days = TimeUtils.getDaysFromMillis(remaining)
        if (days > 0) return context.i18nPlural(R.plurals.duration_days_days, days, "days" to days)
        val hours = TimeUtils.getHoursFromMillis(remaining)
        val minutes = TimeUtils.getMinutesFromMillis(remaining - hours * MILLIS_PER_HOUR)
        val seconds = TimeUtils.getSecondsFromMillis(
            remaining - hours * MILLIS_PER_HOUR - minutes * MILLIS_PER_MINUTE,
        )
        return if (remaining > 0) {
            context.i18nFormat(
                R.string.duration_hours_minutes_seconds,
                "hours" to twoDigits(hours),
                "minutes" to twoDigits(minutes),
                "seconds" to twoDigits(seconds),
            )
        } else {
            context.getString(R.string.sticker_picker_pack_expiring_soon)
        }
    }

    private fun twoDigits(value: Int): String = value.toString().padStart(2, '0')

    private fun strikethrough(context: Context, text: CharSequence): CharSequence =
        SpannableStringBuilder(text).apply {
            setSpan(StrikethroughSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(
                ForegroundColorSpan(ContextCompat.getColor(context, R.color.white)),
                0,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

    private fun concat(bold: CharSequence, separator: String, tail: CharSequence): CharSequence =
        SpannableStringBuilder(bold).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(separator)
            append(tail)
        }

    private const val MILLIS_PER_HOUR = 3_600_000L
    private const val MILLIS_PER_MINUTE = 60_000L
}

/**
 * "5 stickers" for a pack header.
 *
 * `ViewExtensions.setPluralText` cannot be used: it forwards to `getQuantityString(res, n, n)`,
 * which is Android's positional formatting, and Discord's plurals carry a `{#}` token instead.
 */
internal fun android.widget.TextView.setStickerCountText(count: Int) {
    text = context.i18nPlural(R.plurals.sticker_pack_sticker_count_numStickers, count, "#" to count)
}
