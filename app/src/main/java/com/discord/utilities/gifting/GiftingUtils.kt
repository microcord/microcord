package com.discord.utilities.gifting

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.discord.R
import com.discord.api.premium.PremiumTier
import com.discord.i18n.FormatUtils
import com.discord.utilities.billing.GooglePlayInAppSku
import com.discord.utilities.billing.premiumTier1Month
import com.discord.utilities.billing.premiumTier1Year
import com.discord.utilities.billing.premiumTier2Month
import com.discord.utilities.billing.premiumTier2Year
import com.discord.utilities.time.TimeUtils

/**
 * Gift links, gift art, and the countdown shown on an unclaimed gift.
 *
 * The purchase flow itself (`buyGift`) is not reconstructed: it drives Google Play Billing through
 * `GooglePlayBillingManager`/`StoreGooglePlayPurchases`, neither of which exists in this project.
 * Everything here is the presentation side, which the chat gift row and the gift sheet need
 * regardless of whether purchasing works.
 */
object GiftingUtils {

    /** The application every Nitro SKU belongs to. */
    const val PREMIUM_SUBSCRIPTION_APPLICATION_ID: Long = 521_842_831_262_875_670L

    /** SKU ids for the two Nitro subscription products, used to tag a gift with its tier. */
    private const val NITRO_CLASSIC_SKU_ID = 521_846_918_637_420_545L
    private const val NITRO_SKU_ID = 521_847_234_246_082_599L

    /** The shareable form of a gift code. */
    fun generateGiftUrl(giftCode: String): String = "https://discord.gift/$giftCode"

    /**
     * The Nitro tier a store SKU grants, or null for anything that is not a Nitro subscription.
     *
     * `TIER_1` is Nitro Classic and `TIER_2` is full Nitro — the SKU ids, not the names, are what
     * make that mapping unambiguous.
     */
    fun getTierForSku(skuId: Long?): PremiumTier? = when (skuId) {
        NITRO_CLASSIC_SKU_ID -> PremiumTier.TIER_1
        NITRO_SKU_ID -> PremiumTier.TIER_2
        else -> null
    }

    /** Plan artwork for a purchasable gift SKU. */
    @DrawableRes
    fun getIconForSku(inAppSku: GooglePlayInAppSku): Int? = when (inAppSku.paymentGatewaySkuId) {
        premiumTier1Month.paymentGatewaySkuId -> R.drawable.ic_plan_premium_tier_1
        premiumTier1Year.paymentGatewaySkuId -> R.drawable.ic_plan_premium_tier_1_year
        premiumTier2Month.paymentGatewaySkuId -> R.drawable.ic_plan_premium_tier_2
        premiumTier2Year.paymentGatewaySkuId -> R.drawable.ic_plan_premium_tier_2_year
        else -> null
    }

    /**
     * Humanised time remaining on a gift: whole hours while any remain, otherwise minutes.
     *
     * Never seconds and never a combined "1h 20m" — a gift's expiry is coarse by design, and
     * rounding down means the label never claims more time than is left.
     *
     * The reference routes this through `utilities/resources/DurationUtils.formatDuration`, which
     * this project has not reconstructed; the same two string/plural pairs are used directly.
     */
    fun getTimeString(millis: Long, context: Context): CharSequence {
        val hours = TimeUtils.getHoursFromMillis(millis)
        return if (hours > 0) {
            formatDuration(
                context,
                R.string.duration_hours,
                R.plurals.duration_hours_hours,
                "hours",
                hours,
            )
        } else {
            formatDuration(
                context,
                R.string.duration_mins,
                R.plurals.duration_mins_mins,
                "mins",
                TimeUtils.getMinutesFromMillis(millis),
            )
        }
    }

    /**
     * Two nested substitutions: the plural supplies "3 hours", the wrapper string places it in the
     * sentence. Both use the same `{token}` name, which is what keeps the pair swappable per unit.
     */
    private fun formatDuration(
        context: Context,
        @StringRes wrapperRes: Int,
        @PluralsRes pluralRes: Int,
        token: String,
        quantity: Int,
    ): CharSequence {
        val amount = FormatUtils.format(
            context.resources.getQuantityString(pluralRes, quantity),
            token to quantity,
        )
        return FormatUtils.format(context.getString(wrapperRes), token to amount)
    }

    /**
     * Store SKU kinds.
     *
     * [id] is the wire value and the only thing that identifies a kind. The reference declares
     * these alphabetically, so its ordinals do not match the ids; they are declared in id order
     * here because nothing depends on the ordinal.
     */
    enum class SkuTypes(val id: Int) {
        DURABLE_PRIMARY(1),
        DURABLE(2),
        CONSUMABLE(3),
        BUNDLE(4),
        SUBSCRIPTION(5),
    }
}
