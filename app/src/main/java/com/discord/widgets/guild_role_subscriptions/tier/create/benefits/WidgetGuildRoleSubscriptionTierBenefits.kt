package com.discord.widgets.guild_role_subscriptions.tier.create.benefits

import android.graphics.Rect
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.appActivityViewModels
import com.discord.app.appViewModels
import com.discord.utilities.recycler.SelfHealingLinearLayoutManager
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.views.StickyHeaderItemDecoration
import com.discord.widgets.guild_role_subscriptions.getFormattedLabel
import com.discord.widgets.guild_role_subscriptions.tier.create.GuildRoleSubscriptionTierViewModel
import com.discord.widgets.hubs.RadioSelectorItem
import com.discord.widgets.hubs.RadioSelectorItems
import com.discord.widgets.hubs.WidgetRadioSelectorBottomSheet
import com.google.android.material.radiobutton.MaterialRadioButton

class WidgetGuildRoleSubscriptionTierBenefits :
    AppFragment(R.layout.widget_guild_role_subscription_tier_benefits),
    GuildRoleSubscriptionBenefitAdapter.Listener {

    private val guildId: Long
        get() = arguments?.getLong(EXTRA_GUILD_ID, -1L) ?: -1L

    @Suppress("DEPRECATION")
    private val benefitListType: GuildRoleSubscriptionTierBenefitListType
        get() = arguments?.getSerializable(INTENT_EXTRA_BENEFIT_LIST_TYPE)
            as? GuildRoleSubscriptionTierBenefitListType
            ?: GuildRoleSubscriptionTierBenefitListType.ALL

    private val hideHeader: Boolean
        get() = arguments?.getBoolean(INTENT_EXTRA_BENEFITS_HIDE_HEADER, false) ?: false

    private val tierViewModel: GuildRoleSubscriptionTierViewModel by appActivityViewModels {
        GuildRoleSubscriptionTierViewModel()
    }
    private val benefitsViewModel: GuildRoleSubscriptionTierBenefitsViewModel by appViewModels {
        GuildRoleSubscriptionTierBenefitsViewModel(guildId, benefitListType)
    }

    private val benefitLauncher = WidgetGuildRoleSubscriptionTierBenefit.registerForResult(this) { existing, benefit ->
        tierViewModel.updateBenefit(existing, benefit)
    }

    private lateinit var benefitsAdapter: GuildRoleSubscriptionBenefitAdapter
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var channelSettings: RadioGroup
    private lateinit var allChannels: MaterialRadioButton
    private lateinit var manualChannels: MaterialRadioButton
    private var latestViewState: GuildRoleSubscriptionTierBenefitsViewModel.ViewState? = null

    private val channelBenefitSettingsCheckedChangeListener =
        RadioGroup.OnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.guild_role_subscription_tier_all_channels ->
                    tierViewModel.setCanAccessAllChannels(true)
                R.id.guild_role_subscription_tier_manual_channels ->
                    tierViewModel.setCanAccessAllChannels(false)
            }
        }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        title = requireNotNull(view.findViewById(R.id.guild_role_subscription_tier_benefits_title))
        subtitle = requireNotNull(view.findViewById(R.id.guild_role_subscription_tier_benefits_sub_title))
        channelSettings = requireNotNull(
            view.findViewById(R.id.guild_role_subscription_tier_channel_benefit_setting),
        )
        allChannels = requireNotNull(view.findViewById(R.id.guild_role_subscription_tier_all_channels))
        manualChannels = requireNotNull(view.findViewById(R.id.guild_role_subscription_tier_manual_channels))

        val recycler = requireNotNull<RecyclerView>(
            view.findViewById(R.id.subscription_tier_benefits_recycler),
        )
        benefitsAdapter = GuildRoleSubscriptionBenefitAdapter(this, this)
        recycler.layoutManager = SelfHealingLinearLayoutManager(recycler, benefitsAdapter)
        recycler.adapter = benefitsAdapter
        recycler.addItemDecoration(VerticalSpacingDecoration(dpToPx(8)))
        recycler.addItemDecoration(StickyHeaderItemDecoration(benefitsAdapter))

        val headerVisibility = if (hideHeader) View.GONE else View.VISIBLE
        title.visibility = headerVisibility
        subtitle.visibility = headerVisibility
        requireNotNull<View>(view.findViewById(R.id.guild_role_subscription_tier_benefits_divider)).visibility =
            headerVisibility

        when (benefitListType) {
            GuildRoleSubscriptionTierBenefitListType.CHANNEL -> setupUIForChannelBenefits()
            GuildRoleSubscriptionTierBenefitListType.INTANGIBLE -> setupUIForIntangibleBenefits()
            GuildRoleSubscriptionTierBenefitListType.ALL -> {
                setupUIForChannelBenefits()
                setupUIForIntangibleBenefits()
            }
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        tierViewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildRoleSubscriptionTierBenefits::class.java) {
                benefitsViewModel.updateBenefits(
                    it.guildRoleSubscriptionTier,
                    it.isFullServerGating,
                )
            }
        benefitsViewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildRoleSubscriptionTierBenefits::class.java, onNext = ::configureUI)
    }

    private fun configureUI(state: GuildRoleSubscriptionTierBenefitsViewModel.ViewState) {
        latestViewState = state
        benefitsAdapter.setItems(state.items)
        channelSettings.visibility = if (state.canChangeChannelSettings) View.VISIBLE else View.GONE
        state.canAccessAllChannels?.let { canAccessAll ->
            channelSettings.setOnCheckedChangeListener(null)
            if (canAccessAll) allChannels.isChecked = true else manualChannels.isChecked = true
            channelSettings.setOnCheckedChangeListener(channelBenefitSettingsCheckedChangeListener)
        }
    }

    private fun setupUIForChannelBenefits() {
        title.setText(R.string.guild_role_subscription_create_tier_channel_benefits_title)
        subtitle.setText(R.string.guild_role_subscription_create_tier_channel_benefits_description)
        channelSettings.visibility = View.VISIBLE
        channelSettings.setOnCheckedChangeListener(channelBenefitSettingsCheckedChangeListener)
    }

    private fun setupUIForIntangibleBenefits() {
        title.setText(R.string.guild_role_subscription_create_tier_intangible_benefits_title)
        subtitle.setText(R.string.guild_role_subscription_create_tier_intangible_benefits_description)
        channelSettings.visibility = View.GONE
    }

    override fun onBenefitItemClick(benefitAdapterItem: GuildRoleSubscriptionBenefitAdapterItem) {
        val item = benefitAdapterItem as? GuildRoleSubscriptionBenefitAdapterItem.BenefitItem ?: return
        WidgetGuildRoleSubscriptionTierBenefit.launch(
            requireContext(),
            benefitLauncher,
            item.guildId,
            item.type,
            item.tierName,
            item.benefit,
        )
    }

    override fun onAddBenefitItemClick(
        benefitAdapterItem: GuildRoleSubscriptionBenefitAdapterItem.AddBenefitItem,
    ) = WidgetGuildRoleSubscriptionTierBenefit.launch(
        requireContext(),
        benefitLauncher,
        guildId,
        benefitAdapterItem.type,
        benefitAdapterItem.tierName,
        null,
    )

    override fun onFreeTrialToggle(isEnabled: Boolean) {
        tierViewModel.updateFreeTrialInterval(
            if (isEnabled) latestViewState?.trialIntervalOptions?.firstOrNull() else null,
        )
    }

    override fun onActiveTrialUserLimitClick() {
        val options = latestViewState?.activeTrialUserLimitOptions.orEmpty()
        val selected = latestViewState?.items?.filterIsInstance<GuildRoleSubscriptionBenefitAdapterItem.FreeTrialItem>()
            ?.firstOrNull()?.activeTrialUserLimit
        WidgetRadioSelectorBottomSheet.show(
            parentFragmentManager,
            RadioSelectorItems(
                getString(R.string.guild_role_subscription_setup_tier_active_trial_user_limit_title),
                options.mapIndexed { index, value ->
                    RadioSelectorItem(
                        index,
                        value?.toString()
                            ?: getString(R.string.guild_role_subscription_setup_tier_active_trial_user_limit_none_option),
                        value == selected,
                    )
                },
            ),
        ) { index -> tierViewModel.updateFreeTrialLimit(options[index]) }
    }

    override fun onTrialIntervalClick() {
        val options = latestViewState?.trialIntervalOptions.orEmpty()
        val selected = latestViewState?.items?.filterIsInstance<GuildRoleSubscriptionBenefitAdapterItem.FreeTrialItem>()
            ?.firstOrNull()?.trialInterval
        WidgetRadioSelectorBottomSheet.show(
            parentFragmentManager,
            RadioSelectorItems(
                getString(R.string.guild_role_subscription_setup_free_trial_time_limit_title),
                options.mapIndexed { index, value ->
                    RadioSelectorItem(index, value.getFormattedLabel(requireContext()).toString(), value == selected)
                },
            ),
        ) { index -> tierViewModel.updateFreeTrialInterval(options[index]) }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private class VerticalSpacingDecoration(private val spacing: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            outRect.top = spacing
        }
    }

    companion object {
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val INTENT_EXTRA_BENEFITS_HIDE_HEADER = "INTENT_EXTRA_BENEFITS_HIDE_HEADER"
        const val INTENT_EXTRA_BENEFIT_LIST_TYPE = "INTENT_EXTRA_BENEFIT_LIST_TYPE"

        @JvmOverloads
        fun newInstance(
            guildId: Long,
            benefitListType: GuildRoleSubscriptionTierBenefitListType,
            hideHeader: Boolean = false,
        ) = WidgetGuildRoleSubscriptionTierBenefits().apply {
            arguments = bundleOf(
                EXTRA_GUILD_ID to guildId,
                INTENT_EXTRA_BENEFITS_HIDE_HEADER to hideHeader,
                INTENT_EXTRA_BENEFIT_LIST_TYPE to benefitListType,
            )
        }
    }
}
