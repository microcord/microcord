@file:JvmName("ServerSettingsGuildRoleSubscriptionTierListItemGeneratorKt")

package com.discord.widgets.servers.guild_role_subscription

import com.discord.api.guildrolesubscription.GuildRoleSubscriptionTierListing
import com.discord.widgets.guild_role_subscriptions.getPrice

fun generateServerSettingsGuildRoleSubscriptionTierListItems(
    tierListings: List<GuildRoleSubscriptionTierListing>?,
    maxTierCount: Int,
): List<ServerSettingsGuildRoleSubscriptionTierAdapterItem> = buildList {
    tierListings.orEmpty().forEach { tier ->
        add(
            ServerSettingsGuildRoleSubscriptionTierAdapterItem.Tier(
                tier.id,
                tier.name,
                tier.getPrice(),
                tier.applicationId,
                tier.published,
                tier.imageAsset?.id,
            ),
        )
    }
    if (tierListings.orEmpty().size < maxTierCount) {
        add(ServerSettingsGuildRoleSubscriptionTierAdapterItem.AddTier)
    }
}
