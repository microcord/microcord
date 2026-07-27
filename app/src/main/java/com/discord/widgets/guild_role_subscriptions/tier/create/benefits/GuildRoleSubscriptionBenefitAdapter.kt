package com.discord.widgets.guild_role_subscriptions.tier.create.benefits

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppComponent
import com.discord.utilities.recycler.DiffCreator
import com.discord.utilities.views.StickyHeaderItemDecoration
import com.discord.widgets.guild_role_subscriptions.GuildRoleSubscriptionBenefitItemView

class GuildRoleSubscriptionBenefitAdapter(
    private val listener: Listener,
    appComponent: AppComponent,
    private val diffCreator: DiffCreator<List<GuildRoleSubscriptionBenefitAdapterItem>, GuildRoleSubscriptionBenefitViewHolder> =
        DiffCreator(appComponent),
) : RecyclerView.Adapter<GuildRoleSubscriptionBenefitViewHolder>(),
    StickyHeaderItemDecoration.StickyHeaderAdapter {

    private var items: List<GuildRoleSubscriptionBenefitAdapterItem> = emptyList()
    private var headerIndices: List<Int> = emptyList()
    private var stickyHeaderManager: StickyHeadersManager? = null

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is GuildRoleSubscriptionBenefitAdapterItem.BenefitItem -> VIEW_TYPE_BENEFIT
        is GuildRoleSubscriptionBenefitAdapterItem.Header -> VIEW_TYPE_HEADER
        is GuildRoleSubscriptionBenefitAdapterItem.AddBenefitItem -> VIEW_TYPE_ADD_ITEM
        is GuildRoleSubscriptionBenefitAdapterItem.FreeTrialItem -> VIEW_TYPE_TRIALS
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): GuildRoleSubscriptionBenefitViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_BENEFIT -> GuildRoleSubscriptionBenefitViewHolder.ItemViewHolder(
                GuildRoleSubscriptionBenefitItemView(parent.context),
                listener,
            )
            VIEW_TYPE_HEADER -> GuildRoleSubscriptionBenefitViewHolder.HeaderViewHolder(
                inflater.inflate(R.layout.view_guild_role_subscription_section_header_item, parent, false),
            )
            VIEW_TYPE_ADD_ITEM -> GuildRoleSubscriptionBenefitViewHolder.AddItemViewHolder(
                inflater.inflate(R.layout.view_guild_role_subscription_add_benefit_item, parent, false),
                listener,
            )
            VIEW_TYPE_TRIALS -> GuildRoleSubscriptionBenefitViewHolder.FreeTrialViewHolder(
                inflater.inflate(R.layout.view_guild_role_subscription_free_trial_item, parent, false),
                listener,
            )
            else -> throw IllegalArgumentException("Invalid benefit view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: GuildRoleSubscriptionBenefitViewHolder, position: Int) {
        holder.configure(items[position])
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        stickyHeaderManager = StickyHeadersManager(recyclerView).also { manager ->
            recyclerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                manager.layoutViews(recyclerView)
            }
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        stickyHeaderManager = null
    }

    override fun getAndBindHeaderView(position: Int): View? {
        val header = items.getOrNull(position) as? GuildRoleSubscriptionBenefitAdapterItem.Header
            ?: return null
        return stickyHeaderManager?.run {
            stickyHeaderHolder.configure(header)
            currentStickyHeaderView
        }
    }

    override fun getHeaderPositionForItem(itemPosition: Int): Int? =
        headerIndices.lastOrNull { itemPosition >= it }

    override fun isHeader(position: Int): Boolean =
        items.getOrNull(position) is GuildRoleSubscriptionBenefitAdapterItem.Header

    fun clearItems() = setItems(emptyList())

    fun setItems(newItems: List<GuildRoleSubscriptionBenefitAdapterItem>) {
        diffCreator.dispatchDiffUpdates(this, { items = it }, items, newItems)
        headerIndices = newItems.indices.filter { newItems[it] is GuildRoleSubscriptionBenefitAdapterItem.Header }
    }

    interface Listener {
        fun onActiveTrialUserLimitClick()
        fun onAddBenefitItemClick(benefitAdapterItem: GuildRoleSubscriptionBenefitAdapterItem.AddBenefitItem)
        fun onBenefitItemClick(benefitAdapterItem: GuildRoleSubscriptionBenefitAdapterItem)
        fun onFreeTrialToggle(isEnabled: Boolean)
        fun onTrialIntervalClick()
    }

    class StickyHeadersManager(recyclerView: RecyclerView) {
        val stickyHeaderHolder = GuildRoleSubscriptionBenefitViewHolder.HeaderViewHolder(
            LayoutInflater.from(recyclerView.context).inflate(
                R.layout.view_guild_role_subscription_section_header_item,
                recyclerView,
                false,
            ),
        )
        val currentStickyHeaderView: View = stickyHeaderHolder.itemView

        fun layoutViews(recyclerView: RecyclerView) {
            StickyHeaderItemDecoration.LayoutManager.layoutHeaderView(
                recyclerView,
                currentStickyHeaderView,
            )
        }
    }

    private companion object {
        const val VIEW_TYPE_BENEFIT = 0
        const val VIEW_TYPE_HEADER = 2
        const val VIEW_TYPE_ADD_ITEM = 3
        const val VIEW_TYPE_TRIALS = 4
    }
}
