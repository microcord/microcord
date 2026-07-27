package com.discord.widgets.guild_role_subscriptions.setup

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.core.os.bundleOf
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appActivityViewModels
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.views.steps.StepsView
import com.discord.widgets.guild_role_subscriptions.tier.create.GuildRoleSubscriptionTierViewModel
import com.discord.widgets.guild_role_subscriptions.tier.create.WidgetGuildRoleSubscriptionReview
import com.discord.widgets.guild_role_subscriptions.tier.create.WidgetGuildRoleSubscriptionTierDesign
import com.discord.widgets.guild_role_subscriptions.tier.create.WidgetGuildRoleSubscriptionTierDetails
import com.discord.widgets.guild_role_subscriptions.tier.create.benefits.GuildRoleSubscriptionTierBenefitListType
import com.discord.widgets.guild_role_subscriptions.tier.create.benefits.WidgetGuildRoleSubscriptionTierBenefits

/**
 * The seven-step wizard that turns a guild's monetisation on: format → plan details → tier details
 * → channel benefits → other benefits → tier design → review.
 *
 * The host owns nothing but the pager. Each step edits one of two activity-scoped view models —
 * [GuildRoleSubscriptionPlanDetailsViewModel] for the storefront and
 * [GuildRoleSubscriptionTierViewModel] for the tier — and this screen forwards both into
 * [GuildRoleSubscriptionPlanSetupViewModel], which is what actually submits.
 *
 * Every step disables swiping, so Next/Back are the only way through and the gate on Next is the
 * single place validation lives.
 */
class WidgetGuildRoleSubscriptionPlanSetup : AppFragment(R.layout.widget_guild_subscription_plan_setup) {

    private val guildId: Long by lazy { mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, 0L) }

    private val setupViewModel: GuildRoleSubscriptionPlanSetupViewModel by appActivityViewModels {
        GuildRoleSubscriptionPlanSetupViewModel(guildId)
    }

    private val subscriptionPlanDetailsViewModel: GuildRoleSubscriptionPlanDetailsViewModel
        by appActivityViewModels { GuildRoleSubscriptionPlanDetailsViewModel() }

    private val createTierViewModel: GuildRoleSubscriptionTierViewModel
        by appActivityViewModels { GuildRoleSubscriptionTierViewModel() }

    private lateinit var stepsView: StepsView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        stepsView = requireNotNull(view.findViewById(R.id.guild_subscription_plan_setup_step_views))

        val benefitsArguments = { listType: GuildRoleSubscriptionTierBenefitListType ->
            bundleOf(
                EXTRA_GUILD_ID to guildId,
                WidgetGuildRoleSubscriptionTierBenefits.INTENT_EXTRA_BENEFIT_LIST_TYPE to listType,
            )
        }
        val next = { _: View -> setupViewModel.goToNextStep() }
        val steps = listOf(
            wizardStep(WidgetGuildRoleSubscriptionPlanFormat::class.java, nextAction = next),
            wizardStep(WidgetGuildRoleSubscriptionPlanDetails::class.java, nextAction = next),
            wizardStep(WidgetGuildRoleSubscriptionTierDetails::class.java, nextAction = next),
            wizardStep(
                WidgetGuildRoleSubscriptionTierBenefits::class.java,
                arguments = benefitsArguments(GuildRoleSubscriptionTierBenefitListType.CHANNEL),
                nextAction = next,
            ),
            wizardStep(
                WidgetGuildRoleSubscriptionTierBenefits::class.java,
                arguments = benefitsArguments(GuildRoleSubscriptionTierBenefitListType.INTANGIBLE),
                nextAction = next,
            ),
            wizardStep(WidgetGuildRoleSubscriptionTierDesign::class.java, nextAction = next),
            StepsView.FragmentStep(
                fragment = WidgetGuildRoleSubscriptionReview::class.java,
                doneLabel = R.string.guild_role_subscription_plan_finish,
                arguments = bundleOf(
                    EXTRA_GUILD_ID to guildId,
                    WidgetGuildRoleSubscriptionReview.ARG_IS_SUBSCRIPTION_GROUP_PLAN_REVIEW_MODE to true,
                ),
                doneAction = { setupViewModel.submit() },
                cancelAllowed = false,
                scrollingAllowed = false,
            ),
        )
        stepsView.setup(StepsView.PagerAdapter(this, steps), onClose = ::onWizardClosed)
        stepsView.setStepProgressIndicatorVisible(true)
        setOnBackPressed({
            setupViewModel.goToPreviousStep()
            true
        })
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        setupViewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildRoleSubscriptionPlanSetup::class.java) { configureUI(it) }
        setupViewModel.observeEvents()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildRoleSubscriptionPlanSetup::class.java) { handleEvent(it) }
        createTierViewModel.observeViewState()
            .distinctUntilChanged()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildRoleSubscriptionPlanSetup::class.java) {
                setupViewModel.onTierUpdated(it.guildRoleSubscriptionTier)
            }
        subscriptionPlanDetailsViewModel.observeViewState()
            .distinctUntilChanged()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildRoleSubscriptionPlanSetup::class.java) { viewState ->
                setupViewModel.onSubscriptionPlanUpdated(
                    viewState.coverImage,
                    viewState.description,
                    viewState.isFullServerGate,
                )
                // The tier's "all channels" default follows the plan's gating mode.
                createTierViewModel.setFullServerGating(viewState.isFullServerGate)
            }
    }

    private fun configureUI(viewState: GuildRoleSubscriptionPlanSetupViewModel.ViewState) {
        stepsView.setIsNextButtonEnabled(viewState.canProceedToNextStep)
        stepsView.setIsDoneButtonEnabled(!viewState.isSubmitting)
        stepsView.setIsLoading(viewState.isSubmitting)
        stepsView.setCurrentPage(viewState.currentStep)
    }

    private fun handleEvent(event: GuildRoleSubscriptionPlanSetupViewModel.Event) {
        when (event) {
            is GuildRoleSubscriptionPlanSetupViewModel.Event.SubmitSuccess -> {
                onSetupFinished(requireContext(), guildId)
                activity?.finish()
            }
            is GuildRoleSubscriptionPlanSetupViewModel.Event.SubmitFailure ->
                event.error.showToasts(requireContext())
        }
    }

    private fun onWizardClosed() {
        onSetupAbandoned(requireContext(), guildId)
        requireActivity().finish()
    }

    private fun wizardStep(
        fragment: Class<out AppFragment>,
        arguments: android.os.Bundle? = null,
        nextAction: (View) -> Unit,
    ) = StepsView.FragmentStep(
        fragment = fragment,
        nextLabel = R.string.guild_role_subscription_create_tier_continue,
        arguments = arguments,
        nextAction = nextAction,
        cancelAllowed = false,
        scrollingAllowed = false,
    )

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"

        /**
         * Where the wizard goes once the storefront exists.
         *
         * Reference: `WidgetServerSettingsGuildRoleSubscriptionTierList.launch`. That screen lives
         * in `widgets/servers/guild_role_subscription`, which has not been reconstructed, so the
         * default only closes the wizard. Install the real destination when it lands.
         */
        var onSetupFinished: (context: Context, guildId: Long) -> Unit = { _, _ -> }

        /**
         * Where the wizard goes when the creator backs out.
         *
         * Reference: `WidgetServerSettingsEmptyGuildRoleSubscriptions.launch`, same package and the
         * same caveat as [onSetupFinished].
         */
        var onSetupAbandoned: (context: Context, guildId: Long) -> Unit = { _, _ -> }

        fun launch(context: Context, guildId: Long) {
            AppScreenRouter.launch(
                context,
                WidgetGuildRoleSubscriptionPlanSetup::class.java,
                Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId),
            )
        }
    }
}
