package com.discord.utilities.developer

import com.discord.api.premium.PremiumTier
import com.discord.utilities.cache.SharedPreferencesProvider

object DeveloperSettings {
    private const val PREMIUM_TIER_OVERRIDE = "developer_premium_tier_override"
    private const val VERBOSE_BOOT = "developer_verbose_boot"
    private const val FLOATING_SHORTCUT = "developer_floating_shortcut"
    private const val FLOATING_SHORTCUT_X = "developer_floating_shortcut_x"
    private const val FLOATING_SHORTCUT_Y = "developer_floating_shortcut_y"
    private const val LEGACY_MESSAGE_UPLOADS = "developer_legacy_message_uploads"
    const val ACTUAL_TIER = -1

    /** Session-durable: a switch of accounts is not a reason to stop logging this device. */
    fun isVerboseBootEnabled(): Boolean = runCatching {
        SharedPreferencesProvider.getPrefsSessionDurable().getBoolean(VERBOSE_BOOT, false)
    }.getOrDefault(false)

    fun setVerboseBootEnabled(enabled: Boolean) {
        SharedPreferencesProvider.getPrefsSessionDurable().edit()
            .putBoolean(VERBOSE_BOOT, enabled).apply()
    }

    fun isFloatingShortcutEnabled(): Boolean = runCatching {
        SharedPreferencesProvider.getPrefsSessionDurable().getBoolean(FLOATING_SHORTCUT, false)
    }.getOrDefault(false)

    fun setFloatingShortcutEnabled(enabled: Boolean) {
        SharedPreferencesProvider.getPrefsSessionDurable().edit()
            .putBoolean(FLOATING_SHORTCUT, enabled).apply()
    }

    fun isLegacyMessageUploadsEnabled(): Boolean = runCatching {
        SharedPreferencesProvider.getPrefsSessionDurable().getBoolean(LEGACY_MESSAGE_UPLOADS, false)
    }.getOrDefault(false)

    fun setLegacyMessageUploadsEnabled(enabled: Boolean) {
        SharedPreferencesProvider.getPrefsSessionDurable().edit()
            .putBoolean(LEGACY_MESSAGE_UPLOADS, enabled).apply()
    }

    fun floatingShortcutPosition(): Pair<Float, Float> = runCatching {
        val preferences = SharedPreferencesProvider.getPrefsSessionDurable()
        preferences.getFloat(FLOATING_SHORTCUT_X, 1f) to preferences.getFloat(FLOATING_SHORTCUT_Y, 1f)
    }.getOrDefault(1f to 1f)

    fun setFloatingShortcutPosition(x: Float, y: Float) {
        SharedPreferencesProvider.getPrefsSessionDurable().edit()
            .putFloat(FLOATING_SHORTCUT_X, x.coerceIn(0f, 1f))
            .putFloat(FLOATING_SHORTCUT_Y, y.coerceIn(0f, 1f))
            .apply()
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
