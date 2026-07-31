package com.discord.widgets.servers.guild_role_subscription

import com.discord.utilities.recycler.DiffKeyProvider

sealed class ServerSettingsGuildRoleSubscriptionTierAdapterItem : DiffKeyProvider {
    object AddTier : ServerSettingsGuildRoleSubscriptionTierAdapterItem() {
        override val key = "AddTierItem"
    }

    data class Tier(
        val tierListingId: Long,
        val tierName: String,
        val tierPrice: Int,
        val applicationId: Long,
        val isPublished: Boolean,
        val tierImageAssetId: Long?,
    ) : ServerSettingsGuildRoleSubscriptionTierAdapterItem() {
        override val key = tierListingId.toString()
    }
}
