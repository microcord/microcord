package com.discord.widgets.servers.guildboost

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppViewFlipper
import com.discord.app.appViewModels
import com.discord.i18n.i18nPlural
import com.discord.stores.StoreStream
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.analytics.Traits
import com.discord.utilities.billing.GooglePlayBillingManager
import com.discord.utilities.billing.GooglePlaySku
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.simple_pager.SimplePager
import com.discord.utilities.view.ToastManager
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.views.guildboost.GuildBoostMarketingView
import com.discord.views.guildboost.GuildBoostSubscriptionUpsellView
import com.discord.widgets.settings.guildboost.WidgetSettingsGuildBoost
import com.discord.widgets.settings.premium.WidgetChoosePlan
import com.discord.widgets.settings.premium.WidgetSettingsPremium
import com.discord.widgets.notice.WidgetNoticeDialog
import com.google.android.material.button.MaterialButton
import kotlin.math.max
import kotlin.math.roundToInt

class WidgetGuildBoost : AppFragment(R.layout.widget_server_boost_status) {
    private val guildId by lazy { mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, -1L) }
    private val viewModel: GuildBoostViewModel by appViewModels { GuildBoostViewModel(guildId) }
    private val choosePlanLauncher = WidgetChoosePlan.registerForResult(this) { viewModel.retryClicked() }
    private val toastManager = ToastManager()
    private lateinit var flipper: AppViewFlipper
    private lateinit var pager: SimplePager
    private lateinit var pagerAdapter: PerksPagerAdapter
    private lateinit var levelBackgrounds: List<ImageView>
    private lateinit var levelText: List<TextView?>
    private var wasPagerPageSet = false

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled(false)
        flipper = view.findViewById(R.id.boost_status_flipper)
        pager = view.findViewById(R.id.boost_status_viewpager)
        pagerAdapter = PerksPagerAdapter(guildId)
        pager.wrapHeight = true
        pager.adapter = pagerAdapter
        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) = configureLevelBubbles(position + 1)
        })
        setActionBarOptionsMenu(
            R.menu.menu_premium_guild,
            onSelected = { item ->
                if (item.itemId == R.id.menu_premium_guild) WidgetSettingsGuildBoost.launch(requireContext())
            },
        )
        view.findViewById<LinkifiedTextView>(R.id.boost_status_learn_more).apply {
            setText(R.string.premium_guild_perks_modal_blurb_mobile_learn_more)
            setOnClickListener { WidgetSettingsGuildBoost.launch(context) }
        }
        view.findViewById<LinkifiedTextView>(R.id.boost_status_protip).apply {
            text = context.getString(R.string.premium_guild_perks_modal_protip)
                .replace("{numFreeGuildSubscriptions}", context.i18nPlural(
                    R.plurals.premium_guild_perks_modal_protip_mobile_numFreeGuildSubscriptions,
                    2,
                    "#" to 2,
                ))
            setOnClickListener { WidgetSettingsPremium.launch(context) }
        }
        levelBackgrounds = listOf(
            view.findViewById(R.id.boost_bar_tier_0_iv),
            view.findViewById(R.id.boost_bar_tier_1_iv),
            view.findViewById(R.id.boost_bar_tier_2_iv),
            view.findViewById(R.id.boost_bar_tier_3_iv),
        )
        levelText = listOf(
            null,
            view.findViewById(R.id.boost_bar_tier_1_tv),
            view.findViewById(R.id.boost_bar_tier_2_tv),
            view.findViewById(R.id.boost_bar_tier_3_tv),
        )
        view.findViewById<MaterialButton>(R.id.boost_status_subscribe_button)
            .setOnClickListener { viewModel.subscribeClicked(Traits.Location.Section.HEADER) }
        view.findViewById<MaterialButton>(R.id.boost_status_subscribe_button2)
            .setOnClickListener { viewModel.subscribeClicked(Traits.Location.Section.FOOTER) }
        view.findViewById<View>(R.id.boost_status_retry).setOnClickListener { viewModel.retryClicked() }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        if (guildId == 0L || guildId == -1L) {
            activity?.finish()
            return
        }
        viewModel.observeViewState().distinctUntilChanged().ui(this)
            .appSubscribe(javaClass, onNext = ::configureUI)
        viewModel.observeEvents().ui(this).appSubscribe(javaClass, onNext = ::handleEvent)
    }

    override fun onDestroyView() {
        toastManager.close()
        super.onDestroyView()
    }

    private fun configureUI(state: GuildBoostViewModel.ViewState) {
        when (state) {
            GuildBoostViewModel.ViewState.Loading -> flipper.displayedChild = VIEW_LOADING
            GuildBoostViewModel.ViewState.Failure -> flipper.displayedChild = VIEW_FAILURE
            is GuildBoostViewModel.ViewState.Loaded -> {
                flipper.displayedChild = VIEW_LOADED
                setActionBarTitle(R.string.premium_guild_perks_modal_header)
                setActionBarSubtitle(state.guild.name)
                val tier = state.guild.premiumTier
                val boosts = state.guild.premiumSubscriptionCount
                configureProgressBar(state.guild.id, tier, boosts)
                configureViewPager(tier)
                requireView().findViewById<TextView>(R.id.boost_status_num_boosts).text =
                    requireContext().i18nPlural(
                        R.plurals.premium_guild_perks_modal_header_subscription_count_subscriptions,
                        boosts,
                        "subscriptions" to boosts,
                    )
                requireView().findViewById<GuildBoostSubscriptionUpsellView>(R.id.boost_status_premium_upsell)
                    .configure(state.meUser.premiumTier, false)
                requireView().findViewById<GuildBoostMarketingView>(R.id.boost_status_premium_marketing)
                    .configure(state.meUser.premiumTier) {
                        WidgetSettingsPremium.launch(requireContext())
                    }
            }
        }
    }

    private fun configureProgressBar(guildId: Long, tier: Int, boosts: Int) {
        requireView().findViewById<ProgressBar>(R.id.boost_bar_progress_bar).apply {
            progress = totalProgress(guildId, tier, boosts)
            contentDescription = getString(
                when (tier) {
                    1 -> R.string.premium_guild_tier_1
                    2 -> R.string.premium_guild_tier_2
                    3 -> R.string.premium_guild_tier_3
                    else -> R.string.premium_guild_header_badge_no_tier
                },
            )
        }
        levelBackgrounds[0].isEnabled = boosts > 0
        (1..3).forEach { levelBackgrounds[it].isEnabled = tier >= it }
    }

    private fun configureViewPager(tier: Int) {
        pagerAdapter.premiumTier = tier
        pagerAdapter.configureViews(pager)
        if (!wasPagerPageSet) {
            pager.setCurrentItem(max(0, tier - 1), false)
            configureLevelBubbles(max(1, tier))
            wasPagerPageSet = true
        }
    }

    private fun configureLevelBubbles(selected: Int) {
        levelBackgrounds.forEachIndexed { index, image ->
            val size = (if (index == selected) 32 else 20) * resources.displayMetrics.density
            image.layoutParams = image.layoutParams.apply { width = size.roundToInt(); height = size.roundToInt() }
            levelText[index]?.setTextColor(
                ColorCompat.getThemedColor(image, if (index == selected) R.attr.primary_000 else R.attr.primary_400),
            )
        }
    }

    private fun handleEvent(event: GuildBoostViewModel.Event) {
        when (event) {
            is GuildBoostViewModel.Event.LaunchSubscriptionConfirmation -> {
                AnalyticsTracker.openModal(
                    Traits.Location.Section.PREMIUM_GUILD_SUBSCRIPTION_CONFIRMATION,
                    boostLocation(Traits.Location.Section.HEADER),
                )
                WidgetGuildBoostConfirmation.create(requireContext(), event.guildId, event.slotId)
            }
            is GuildBoostViewModel.Event.LaunchPurchaseSubscription -> {
                WidgetChoosePlan.launch(
                    context = requireContext(),
                    launcher = choosePlanLauncher,
                    viewType = WidgetChoosePlan.ViewType.BUY_PREMIUM_GUILD,
                    oldSkuName = event.oldSkuName,
                    locationTrait = boostLocation(event.section),
                )
            }
            GuildBoostViewModel.Event.ShowDesktopAlertDialog -> {
                AnalyticsTracker.openModal(
                    Traits.Location.Section.ANDROID_CANNOT_MANAGE_SUBSCRIPTION,
                    boostLocation(Traits.Location.Section.HEADER),
                )
                WidgetNoticeDialog.show(
                    parentFragmentManager,
                    getString(R.string.premium_guild_subscription_out_of_slots_title),
                    getString(R.string.premium_guild_subscription_out_of_slots_purchase_on_desktop),
                    getString(R.string.premium_guild_subscription_header_subscribe_tooltip_close),
                )
            }
            GuildBoostViewModel.Event.UnacknowledgedPurchase -> {
                toastManager.show(requireContext(), R.string.billing_error_purchase)
                GooglePlayBillingManager.queryPurchases()
            }
            is GuildBoostViewModel.Event.ShowBlockedPlanSwitchAlertDialog -> WidgetNoticeDialog.show(
                parentFragmentManager,
                getString(event.headerStringRes),
                getString(event.bodyStringRes),
                getString(R.string.billing_manage_subscription),
                getString(R.string.cancel),
                mapOf(R.id.notice_ok to { WidgetSettingsPremium.launch(requireContext()) }),
            )
        }
    }

    private fun boostLocation(section: String) = Traits.Location(
        page = Traits.Location.Page.BOOST_MARKETING,
        section = section,
        _object = Traits.Location.Obj.BUTTON_CTA,
        objectType = Traits.Location.ObjType.BUY,
    )

    private class PerksPagerAdapter(private val guildId: Long) : PagerAdapter() {
        var premiumTier = 0

        fun configureViews(viewPager: ViewPager) {
            (0 until viewPager.childCount).forEach { index ->
                val child = viewPager.getChildAt(index) as? GuildBoostPerkView ?: return@forEach
                child.configure(child.tag as Int, premiumTier, guildId)
            }
        }

        override fun getCount() = 3
        override fun isViewFromObject(view: View, any: Any) = view === any
        override fun destroyItem(container: ViewGroup, position: Int, any: Any) = container.removeView(any as View)
        override fun instantiateItem(container: ViewGroup, position: Int): Any =
            GuildBoostPerkView(container.context).apply {
                val tier = position + 1
                configure(tier, premiumTier, guildId)
                tag = tier
                container.addView(this)
            }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "GUILD_ID"
        private const val VIEW_LOADING = 0
        private const val VIEW_FAILURE = 1
        private const val VIEW_LOADED = 2

        fun create(context: Context, guildId: Long) = AppScreenRouter.launch(
            context,
            WidgetGuildBoost::class.java,
            Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId),
        )

        internal fun totalProgress(guildId: Long, tier: Int, boosts: Int): Int {
            val guild = StoreStream.getGuilds().getGuild(guildId)
            val smallBucket = guild?.takeIf {
                it.hasFeature(com.discord.api.guild.GuildFeature.BOOSTING_TIERS_EXPERIMENT_SMALL_GUILD)
            }?.let {
                StoreStream.getExperiments()
                    .getGuildExperiment("2022-03_boosting_tiers_small_guilds", guildId, true)?.bucket
            }
            val mediumBucket = guild?.takeIf {
                it.hasFeature(com.discord.api.guild.GuildFeature.BOOSTING_TIERS_EXPERIMENT_MEDIUM_GUILD)
            }?.let {
                StoreStream.getExperiments()
                    .getGuildExperiment("2022-03_boosting_tiers_medium_guilds", guildId, true)?.bucket
            }
            val thresholds = when {
                smallBucket == 1 || smallBucket == 2 -> intArrayOf(2, 3, 4)
                mediumBucket == 1 || mediumBucket == 2 -> intArrayOf(2, 7, 10)
                else -> intArrayOf(2, 7, 14)
            }
            return totalProgress(tier, boosts, thresholds)
        }

        internal fun totalProgress(tier: Int, boosts: Int, thresholds: IntArray): Int {
            if (tier >= 3) return 100
            val current = if (tier == 0) 0 else thresholds[tier - 1]
            return (((boosts - current).toFloat() / (thresholds[tier] - current) * 33.3f) + tier * 33.3f)
                .roundToInt().coerceIn(0, 100)
        }

        internal fun hasPurchasableGuildBoostPlan(productIds: Set<String>): Boolean =
            GooglePlaySku.entries.any { it.premiumSubscriptionCount > 0 && it.skuName in productIds }
    }
}
