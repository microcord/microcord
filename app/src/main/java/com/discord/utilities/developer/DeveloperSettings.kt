package com.discord.utilities.developer

import com.discord.api.premium.PremiumTier
import com.discord.utilities.cache.SharedPreferencesProvider

object DeveloperSettings {
    private const val PREMIUM_TIER_OVERRIDE = "developer_premium_tier_override"
    private const val VERBOSE_BOOT = "developer_verbose_boot"
    const val ACTUAL_TIER = -1

    /** Session-durable: a switch of accounts is not a reason to stop logging this device. */
    fun isVerboseBootEnabled(): Boolean = runCatching {
        SharedPreferencesProvider.getPrefsSessionDurable().getBoolean(VERBOSE_BOOT, false)
    }.getOrDefault(false)

    fun setVerboseBootEnabled(enabled: Boolean) {
        SharedPreferencesProvider.getPrefsSessionDurable().edit()
            .putBoolean(VERBOSE_BOOT, enabled).apply()
    }

    fun premiumTierOverride(): PremiumTier? = SharedPreferencesProvider.getOrNull()
        ?.getInt(PREMIUM_TIER_OVERRIDE, ACTUAL_TIER)
        ?.takeIf { it in PremiumTier.entries.indices }
        ?.let(PremiumTier.entries::get)

    fun setPremiumTierOverride(tier: PremiumTier?) {
        SharedPreferencesProvider.get().edit()
            .putInt(PREMIUM_TIER_OVERRIDE, tier?.ordinal ?: ACTUAL_TIER)
            .apply()
    }
}
