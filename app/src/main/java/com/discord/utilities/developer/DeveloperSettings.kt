package com.discord.utilities.developer

import com.discord.api.premium.PremiumTier
import com.discord.utilities.cache.SharedPreferencesProvider

object DeveloperSettings {
    private const val PREMIUM_TIER_OVERRIDE = "developer_premium_tier_override"
    const val ACTUAL_TIER = -1

    fun premiumTierOverride(): PremiumTier? = SharedPreferencesProvider.get()
        .getInt(PREMIUM_TIER_OVERRIDE, ACTUAL_TIER)
        .takeIf { it in PremiumTier.entries.indices }
        ?.let(PremiumTier.entries::get)

    fun setPremiumTierOverride(tier: PremiumTier?) {
        SharedPreferencesProvider.get().edit()
            .putInt(PREMIUM_TIER_OVERRIDE, tier?.ordinal ?: ACTUAL_TIER)
            .apply()
    }
}
