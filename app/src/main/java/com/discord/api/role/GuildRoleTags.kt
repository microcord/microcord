package com.discord.api.role

import com.discord.nullserializable.NullSerializable
import java.io.Serializable

data class GuildRoleTags(
    val premiumSubscriber: NullSerializable<Any>?,
    val botId: String?,
    val integrationId: String?,
    val skuId: String?,
    val subscriptionListingId: String?,
    val purchasableOrHasSubscribers: NullSerializable<Any>?,
) : Serializable {
    val isPurchasableOrHasSubscribers: Boolean
        get() = purchasableOrHasSubscribers != null
}
