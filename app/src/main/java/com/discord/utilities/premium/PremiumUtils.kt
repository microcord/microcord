package com.discord.utilities.premium

import android.content.Context
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import com.discord.R
import com.discord.api.guild.GuildFeature
import com.discord.api.premium.PremiumTier
import com.discord.models.domain.ModelGift
import com.discord.models.domain.ModelSku
import com.discord.models.domain.ModelSubscription
import com.discord.models.domain.premium.SubscriptionPlanType
import com.discord.models.guild.Guild
import com.discord.models.user.User
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.gifting.getCustomStyle
import com.discord.utilities.gifting.hasCustomStyle
import com.discord.utilities.uri.UriHandler
import com.discord.utilities.user.UserUtils

/**
 * The limits and entitlements Nitro and server boosting unlock.
 *
 * Everything here is a pure derivation from a tier number or a feature flag, which is what lets the
 * composer, the emoji picker and the settings screens all agree about a ceiling without any of them
 * talking to a store.
 */
object PremiumUtils {

    const val PREMIUM_APPLICATION_ID: Long = 521_842_831_262_875_670L

    const val MAX_NON_PREMIUM_GUILD_COUNT: Int = 100
    const val MAX_PREMIUM_GUILD_COUNT: Int = 200

    /** Boosts a Nitro subscriber gets bundled with the subscription. */
    const val NUM_FREE_GUILD_BOOSTS_WITH_PREMIUM: Int = 2

    const val GUILD_BOOST_FOR_PREMIUM_USER_DISCOUNT_PERCENT: Float = 0.3f
    const val PREMIUM_YEARLY_DISCOUNT_PERCENT: Float = 0.16f

    /**
     * Guild features gated behind each boost tier, cumulative.
     *
     * Declared as a chain — tier 2 is tier 1 plus its own, tier 3 is tier 2 plus its own — because
     * [getMinimumBoostTierForGuildFeature] walks them in order and returns the first that contains
     * the feature. Flattening them into three independent sets would make that lookup wrong.
     */
    private val tier1BoostFeatures: Set<GuildFeature> =
        setOf(GuildFeature.INVITE_SPLASH, GuildFeature.ANIMATED_ICON)

    private val tier2BoostFeatures: Set<GuildFeature> = tier1BoostFeatures +
        setOf(GuildFeature.BANNER, GuildFeature.PRIVATE_THREADS, GuildFeature.ROLE_ICONS)

    private val tier3BoostFeatures: Set<GuildFeature> = tier2BoostFeatures +
        setOf(GuildFeature.VANITY_URL, GuildFeature.ANIMATED_BANNER)

    /** The lowest boost tier that unlocks [guildFeature], or null if boosting never unlocks it. */
    fun getMinimumBoostTierForGuildFeature(guildFeature: GuildFeature): Int? = when (guildFeature) {
        in tier1BoostFeatures -> 1
        in tier2BoostFeatures -> 2
        in tier3BoostFeatures -> 3
        else -> null
    }

    /**
     * Whether the guild's boost tier reaches the feature's requirement.
     *
     * A feature no tier gates requires tier `0`, so an unboosted guild passes; a null guild is
     * treated as tier `-1` and fails everything, including those.
     */
    fun doesGuildHaveEnoughBoostsForFeature(guild: Guild?, feature: GuildFeature): Boolean =
        (guild?.premiumTier ?: -1) >= (getMinimumBoostTierForGuildFeature(feature) ?: 0)

    /** "Level 1"/"Level 2"/"Level 3" for the badge next to a gated control. */
    fun getBoostTierShortText(context: Context, premiumTier: Int?): CharSequence? = when (premiumTier) {
        1 -> context.getString(R.string.premium_guild_tier_1_short)
        2 -> context.getString(R.string.premium_guild_tier_2_short)
        3 -> context.getString(R.string.premium_guild_tier_3_short)
        else -> null
    }

    /**
     * The badge shown beside a boost-gated control: either "unlocked" in boost pink, or the tier
     * needed, tappable into the boost upsell.
     *
     * [onUnlockedClick] fires when the feature is already available; [onLockedClick] receives the
     * required tier and is the seam for the upsell sheet, which this project has not reconstructed
     * — leave it null and a locked badge renders correctly but does nothing on tap.
     */
    @JvmOverloads
    fun getBoostFeatureBadgeDataForGuildFeature(
        guild: Guild?,
        guildFeature: GuildFeature,
        context: Context,
        onUnlockedClick: ((View) -> Unit)? = null,
        onLockedClick: ((View, minimumBoostTier: Int?) -> Unit)? = null,
    ): BoostFeatureBadgeData {
        if (guild != null && guild.hasFeature(guildFeature)) {
            return BoostFeatureBadgeData(
                onClickListener = onUnlockedClick,
                iconColor = ColorCompat.getColor(context, R.color.guild_boosting_pink),
                text = context.getString(R.string.guild_settings_premium_guild_unlocked),
                textColor = ColorCompat.getThemedColor(context, R.attr.colorTextNormal),
            )
        }
        val minimumBoostTier = getMinimumBoostTierForGuildFeature(guildFeature)
        return BoostFeatureBadgeData(
            onClickListener = onLockedClick?.let { click -> { view -> click(view, minimumBoostTier) } },
            iconColor = ColorCompat.getThemedColor(context, R.attr.colorInteractiveNormal),
            text = getBoostTierShortText(context, minimumBoostTier) ?: "",
            textColor = ColorCompat.getThemedColor(context, R.attr.colorTextMuted),
        )
    }

    /**
     * Custom emoji slots a guild has.
     *
     * `MORE_EMOJI` is a manually granted feature that raises the floor to 200 — but only up to
     * tier 2, because tier 3 already exceeds it.
     */
    fun getGuildEmojiMaxCount(guildPremiumTier: Int, hasMoreEmoji: Boolean): Int = when {
        hasMoreEmoji && guildPremiumTier <= 2 -> 200
        guildPremiumTier == 1 -> 100
        guildPremiumTier == 2 -> 150
        guildPremiumTier == 3 -> 250
        else -> 50
    }

    fun getGuildEmojiMaxCount(guild: Guild): Int =
        getGuildEmojiMaxCount(guild.premiumTier, guild.hasFeature(GuildFeature.MORE_EMOJI))

    /**
     * Sticker slots a guild has.
     *
     * Unlike emoji, an unboosted guild gets **zero** sticker slots — stickers are entirely a boost
     * perk. The `MORE_STICKERS` grant raises it to 60, again capped at tier 2.
     */
    fun getGuildStickerMaxCount(guildPremiumTier: Int, hasMoreSticker: Boolean): Int = when {
        hasMoreSticker && guildPremiumTier <= 2 -> 60
        guildPremiumTier == 1 -> 15
        guildPremiumTier == 2 -> 30
        guildPremiumTier == 3 -> 60
        else -> 0
    }

    /** Upload ceiling in megabytes for a guild at [guildPremiumTier]. */
    fun getGuildMaxFileSizeMB(guildPremiumTier: Int): Int = when (guildPremiumTier) {
        0, 1 -> 8
        2 -> 50
        else -> 100
    }

    /**
     * Upload ceiling in megabytes for a user.
     *
     * Staff get 200 regardless of tier. Note `TIER_0` (Nitro Basic) is *below* `TIER_1` in this
     * enum's declaration order but grants 20 MB, so the checks are by identity, not by rank.
     */
    fun getMaxFileSizeMB(user: User?): Int = when {
        user != null && with(UserUtils) { user.isStaff } -> 200
        user?.premiumTier == PremiumTier.TIER_1 -> 50
        user?.premiumTier == PremiumTier.TIER_2 -> 100
        user?.premiumTier == PremiumTier.TIER_0 -> 20
        else -> 8
    }

    /** Boosts bundled with a subscription — two for any tier-2 (Nitro) plan, none otherwise. */
    fun ModelSubscription.getGuildBoostCountFromSubscription(): Int = when (premiumBasePlanId) {
        SubscriptionPlanType.PREMIUM_MONTH_TIER_2.planId,
        SubscriptionPlanType.PREMIUM_YEAR_TIER_2.planId,
        SubscriptionPlanType.PREMIUM_3_MONTH_TIER_2.planId,
        SubscriptionPlanType.PREMIUM_6_MONTH_TIER_2.planId,
        -> NUM_FREE_GUILD_BOOSTS_WITH_PREMIUM
        else -> 0
    }

    fun findSubscriptionForSku(
        subscriptions: List<ModelSubscription>,
        skuName: String,
    ): ModelSubscription? = subscriptions.firstOrNull { it.paymentGatewayPlanId == skuName }

    fun isNitroSku(sku: ModelSku): Boolean =
        sku.skuCategory == ModelSku.SkuCategory.NITRO ||
            sku.skuCategory == ModelSku.SkuCategory.NITRO_CLASSIC

    /**
     * Rank comparison over [PremiumTier].
     *
     * Uses declaration order, so `NONE < PREMIUM_GUILD_SUBSCRIPTION_ONLY < TIER_0 < TIER_1 <
     * TIER_2` — which is the *entitlement* order, not the marketing order.
     */
    fun isPremiumTierAtLeast(premiumTier: PremiumTier, minimumLevel: PremiumTier): Boolean =
        premiumTier.ordinal >= minimumLevel.ordinal

    /** Google Play reports prices in millionths of a currency unit; this yields cents. */
    fun microAmountToMinor(amount: Long): Int = (amount / 10_000).toInt()

    /**
     * Backdrop for a gift card: a flat colour behind bespoke art (which carries its own
     * background), otherwise the themed Nitro splash drawable.
     *
     * **The two branches return different kinds of int** — a packed ARGB colour and a drawable
     * resource id. That is the reference's own shape, and callers reproduce it by branching on
     * `gift.hasCustomStyle()` themselves; do not feed the result straight into
     * `setBackgroundResource`.
     */
    @DrawableRes
    fun getNitroGiftBackground(gift: ModelGift, context: Context): Int = if (gift.hasCustomStyle()) {
        ColorCompat.getThemedColor(context, R.attr.colorBackgroundSecondaryAlt)
    } else {
        context.getThemedDrawableRes(R.attr.gift_nitro_splash, R.drawable.img_nitro_splash_dark)
    }

    /** Bespoke gift art if the gift has any, else the plain Nitro badge for its SKU. */
    @DrawableRes
    fun getNitroGiftIcon(gift: ModelGift): Int =
        gift.getCustomStyle()?.staticRes ?: getNitroGiftIcon(gift.storeListing?.sku)

    /** `0` for a game SKU or none — the caller hides the badge rather than drawing a placeholder. */
    @DrawableRes
    fun getNitroGiftIcon(sku: ModelSku?): Int = when (sku?.skuCategory) {
        ModelSku.SkuCategory.NITRO -> R.drawable.drawable_ic_nitro
        ModelSku.SkuCategory.NITRO_CLASSIC -> R.drawable.drawable_ic_nitro_classic
        else -> 0
    }

    fun openAppleBilling(context: Context) =
        UriHandler.handle(context, context.getString(R.string.apple_billing_url))

    /**
     * Opens the Play Store subscription management page, deep-linked to [skuName] when known.
     */
    fun openGooglePlayBilling(context: Context, skuName: String?) {
        val url = if (skuName != null) {
            "https://play.google.com/store/account/subscriptions" +
                "?sku=$skuName&package=${context.packageName}"
        } else {
            "https://play.google.com/store/account/subscriptions"
        }
        UriHandler.handle(context, url)
    }

    /**
     * Everything a boost-gated badge needs to render.
     *
     * A null [onClickListener] means the badge is informational — the reference uses that for the
     * "unlocked" state, where there is nothing left to upsell.
     */
    data class BoostFeatureBadgeData(
        val onClickListener: ((View) -> Unit)?,
        @ColorInt val iconColor: Int,
        val text: CharSequence,
        @ColorInt val textColor: Int,
    )
}
