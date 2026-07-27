package com.discord.widgets.guild_role_subscriptions.tier.create

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.appActivityViewModels
import com.discord.app.appViewModels
import com.discord.utilities.recycler.SelfHealingLinearLayoutManager
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.widgets.guild_role_subscriptions.setup.GuildRoleSubscriptionPlanSetupViewModel
import com.discord.widgets.guild_role_subscriptions.tier.GuildRoleSubscriptionTierAdapter
import com.discord.widgets.guild_role_subscriptions.tier.GuildRoleSubscriptionTierItemDecoration

class WidgetGuildRoleSubscriptionReview :
    AppFragment(R.layout.widget_guild_role_subscription_tier_review) {

    private val guildId: Long
        get() = arguments?.getLong(EXTRA_GUILD_ID, -1L) ?: -1L

    private val isPlanReviewMode: Boolean
        get() = arguments?.getBoolean(ARG_IS_SUBSCRIPTION_GROUP_PLAN_REVIEW_MODE, false) ?: false

    private val tierViewModel: GuildRoleSubscriptionTierViewModel by appActivityViewModels {
        GuildRoleSubscriptionTierViewModel()
    }
    private val planSetupViewModel: GuildRoleSubscriptionPlanSetupViewModel by appActivityViewModels {
        GuildRoleSubscriptionPlanSetupViewModel(guildId)
    }
    private val reviewViewModel: GuildRoleSubscriptionReviewViewModel by appViewModels {
        GuildRoleSubscriptionReviewViewModel(guildId)
    }

    private lateinit var adapter: GuildRoleSubscriptionTierAdapter

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val recycler = requireNotNull<RecyclerView>(
            view.findViewById(R.id.subscription_tier_review_recycler),
        )
        adapter = GuildRoleSubscriptionTierAdapter(this)
        recycler.layoutManager = SelfHealingLinearLayoutManager(recycler, adapter)
        recycler.adapter = adapter
        recycler.addItemDecoration(
            GuildRoleSubscriptionTierItemDecoration(
                dpToPx(8),
                resources.getDimensionPixelSize(R.dimen.guild_role_subscription_setup_default_padding),
            ),
        )
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        tierViewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildRoleSubscriptionReview::class.java) { state ->
                val tier = state.guildRoleSubscriptionTier
                if (!tier.name.isNullOrBlank() && tier.memberColor != null && tier.priceTier != null) {
                    reviewViewModel.updateTier(tier, state.isFullServerGating)
                }
            }
        reviewViewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildRoleSubscriptionReview::class.java) { adapter.setItems(it.items) }
        if (isPlanReviewMode) {
            planSetupViewModel.observeViewState()
                .bindToComponentLifecycle(this)
                .appSubscribe(WidgetGuildRoleSubscriptionReview::class.java) { state ->
                    val description = state.planDescription
                    val coverImage = state.coverImage
                    if (!description.isNullOrBlank() && !coverImage.isNullOrBlank()) {
                        reviewViewModel.updatePlanDetails(description, coverImage)
                    }
                }
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        const val ARG_IS_SUBSCRIPTION_GROUP_PLAN_REVIEW_MODE =
            "ARG_IS_SUBSCRIPTION_GROUP_PLAN_REVIEW_MODE"
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
    }
}
