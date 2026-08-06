package com.discord.widgets.servers.guild_role_subscription.upsell

import android.content.SharedPreferences
import com.discord.api.guild.GuildFeature
import com.discord.models.guild.Guild
import com.discord.stores.StoreGuilds
import com.discord.stores.StorePermissions
import com.discord.stores.StoreUser
import com.discord.widgets.guild_role_subscriptions.GuildRoleSubscriptionsFeatureFlag

class GuildRoleSubscriptionOnboardingUpsellManager(
    private val sharedPreferences: SharedPreferences,
    storePermissions: StorePermissions,
    storeUser: StoreUser,
    storeGuilds: StoreGuilds,
) {
    private val featureFlag = GuildRoleSubscriptionsFeatureFlag(
        storeGuilds,
        storeUser,
        storePermissions,
    )

    fun canShow(guild: Guild): Boolean =
        !sharedPreferences.getBoolean(SHOWN_CACHE_KEY, false) &&
            !guild.hasFeature(GuildFeature.CREATOR_MONETIZABLE) &&
            !guild.hasFeature(GuildFeature.CREATOR_MONETIZABLE_DISABLED) &&
            featureFlag.canGuildSeeGuildRoleSubscriptionSettings(guild.id)

    fun setHasBeenShown() {
        sharedPreferences.edit().putBoolean(SHOWN_CACHE_KEY, true).apply()
    }

    companion object {
        const val SHOWN_CACHE_KEY = "HAS_GUILD_ROLE_SUBSCRIPTION_UPSELL_BEEN_SHOWN"
    }
}
