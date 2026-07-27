package com.discord.widgets.guild_role_subscriptions.tier

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.discord.utilities.recycler.DiffKeyProvider
import com.discord.widgets.guild_role_subscriptions.tier.model.Benefit

/** A row of the read-only tier preview shown on the review step and in the tier list. */
sealed class GuildRoleSubscriptionTierAdapterItem : DiffKeyProvider {

    /** The storefront's own cover image and blurb, shown above the tier being reviewed. */
    data class PlanDetails(
        val description: String,
        val coverImage: String,
    ) : GuildRoleSubscriptionTierAdapterItem() {
        override val key: String = "planDetails"
    }

    /**
     * The tier's name, artwork, price and blurb.
     *
     * [skuId] is unused by the preview but carried so the same row can back a purchasable listing.
     */
    data class Header(
        val name: String?,
        val price: Int?,
        val skuId: Long? = null,
        val image: String?,
        val description: String?,
    ) : GuildRoleSubscriptionTierAdapterItem() {
        override val key: String = "header:$name"
    }

    /** A sample chat message rendered in the tier's member colour and badge. */
    data class MemberPreview(
        val memberColor: Int?,
        val memberIcon: String?,
    ) : GuildRoleSubscriptionTierAdapterItem() {
        override val key: String = "member:$memberColor"
    }

    /**
     * A "Exclusive channels" / "3 benefits" divider.
     *
     * Either [titleResId] or the [titlePluralResId]/[formatArgument] pair is set, never both.
     */
    data class SectionHeader(
        @StringRes val titleResId: Int?,
        @PluralsRes val titlePluralResId: Int?,
        val formatArgument: Int?,
    ) : GuildRoleSubscriptionTierAdapterItem() {
        override val key: String = "$titlePluralResId$titleResId"
    }

    /**
     * One listed perk. Keyed by [index] rather than by the benefit, because two benefits may be
     * identical and the list is ordered.
     */
    data class BenefitItem(
        val index: Int,
        val benefit: Benefit,
    ) : GuildRoleSubscriptionTierAdapterItem() {
        override val key: String = index.toString()
    }

    /** Stands in for the whole benefit list when the plan gates the entire server. */
    object AllChannelsAccessBenefitItem : GuildRoleSubscriptionTierAdapterItem() {
        override val key: String = "allChannelsAccessBenefitItem"
    }
}
