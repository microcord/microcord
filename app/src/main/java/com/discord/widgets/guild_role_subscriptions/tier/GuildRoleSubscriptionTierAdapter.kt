package com.discord.widgets.guild_role_subscriptions.tier

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppComponent
import com.discord.utilities.recycler.DiffCreator
import com.discord.widgets.guild_role_subscriptions.GuildRoleSubscriptionBenefitItemView
import com.discord.widgets.guild_role_subscriptions.tier.create.GuildRoleSubscriptionMemberPreview

class GuildRoleSubscriptionTierAdapter(
    appComponent: AppComponent,
    private val diffCreator: DiffCreator<List<GuildRoleSubscriptionTierAdapterItem>, GuildRoleSubscriptionTierViewHolder> =
        DiffCreator(appComponent),
) : RecyclerView.Adapter<GuildRoleSubscriptionTierViewHolder>() {

    private var items: List<GuildRoleSubscriptionTierAdapterItem> = emptyList()

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is GuildRoleSubscriptionTierAdapterItem.Header -> VIEW_TYPE_TIER_HEADER
        is GuildRoleSubscriptionTierAdapterItem.MemberPreview -> VIEW_TYPE_TIER_MEMBER_PREVIEW
        is GuildRoleSubscriptionTierAdapterItem.SectionHeader -> VIEW_TYPE_TIER_SECTION_HEADER
        is GuildRoleSubscriptionTierAdapterItem.BenefitItem -> VIEW_TYPE_TIER_BENEFIT
        is GuildRoleSubscriptionTierAdapterItem.PlanDetails -> VIEW_TYPE_PLAN_DETAILS
        GuildRoleSubscriptionTierAdapterItem.AllChannelsAccessBenefitItem ->
            VIEW_TYPE_ALL_CHANNELS_ACCESS_BENEFIT
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): GuildRoleSubscriptionTierViewHolder = when (viewType) {
        VIEW_TYPE_TIER_HEADER -> GuildRoleSubscriptionTierViewHolder.TierHeaderViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.view_guild_role_subscription_tier_header, parent, false),
        )
        VIEW_TYPE_TIER_MEMBER_PREVIEW -> GuildRoleSubscriptionTierViewHolder.MemberPreviewViewHolder(
            GuildRoleSubscriptionMemberPreview(parent.context),
        )
        VIEW_TYPE_TIER_SECTION_HEADER -> GuildRoleSubscriptionTierViewHolder.SectionHeaderViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.view_guild_role_subscription_section_header_item, parent, false),
        )
        VIEW_TYPE_TIER_BENEFIT, VIEW_TYPE_ALL_CHANNELS_ACCESS_BENEFIT ->
            GuildRoleSubscriptionTierViewHolder.BenefitViewHolder(
                GuildRoleSubscriptionBenefitItemView(parent.context),
            )
        VIEW_TYPE_PLAN_DETAILS -> GuildRoleSubscriptionTierViewHolder.PlanDetailsViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.view_guild_role_subscription_plan_details_review, parent, false),
        )
        else -> throw IllegalArgumentException(
            "invalid viewType $viewType for ${GuildRoleSubscriptionTierAdapter::class.java.simpleName}",
        )
    }

    override fun onBindViewHolder(holder: GuildRoleSubscriptionTierViewHolder, position: Int) {
        holder.bind(items[position])
    }

    fun setItems(newItems: List<GuildRoleSubscriptionTierAdapterItem>) {
        diffCreator.dispatchDiffUpdatesAsync(this, { items = it }, items, newItems)
    }

    companion object {
        const val VIEW_TYPE_TIER_HEADER = 0
        const val VIEW_TYPE_TIER_MEMBER_PREVIEW = 1
        const val VIEW_TYPE_TIER_SECTION_HEADER = 2
        const val VIEW_TYPE_TIER_BENEFIT = 3
        const val VIEW_TYPE_PLAN_DETAILS = 4
        const val VIEW_TYPE_ALL_CHANNELS_ACCESS_BENEFIT = 5
    }
}
