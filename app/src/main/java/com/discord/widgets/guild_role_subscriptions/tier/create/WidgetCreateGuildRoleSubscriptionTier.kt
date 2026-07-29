package com.discord.widgets.guild_role_subscriptions.tier.create

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
import com.discord.widgets.guild_role_subscriptions.tier.create.benefits.GuildRoleSubscriptionTierBenefitListType
import com.discord.widgets.guild_role_subscriptions.tier.create.benefits.WidgetGuildRoleSubscriptionTierBenefits

class WidgetCreateGuildRoleSubscriptionTier :
    AppFragment(R.layout.widget_create_guild_role_subscription_tier) {

    private val guildId: Long by lazy {
        mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, 0L)
    }
    private val groupListingId: Long by lazy {
        mostRecentIntent.getLongExtra(INTENT_EXTRA_GROUP_LISTING_ID, 0L)
    }
    private val createTierViewModel: CreateGuildRoleSubscriptionTierViewModel by appActivityViewModels {
        CreateGuildRoleSubscriptionTierViewModel(guildId, groupListingId)
    }
    private val tierViewModel: GuildRoleSubscriptionTierViewModel by appActivityViewModels {
        GuildRoleSubscriptionTierViewModel()
    }

    private lateinit var stepsView: StepsView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        stepsView = requireNotNull(view.findViewById(R.id.create_guild_subscription_role_tier_steps_view))
        val next = { _: View -> createTierViewModel.goToNextStep() }
        val steps = listOf(
            wizardStep(WidgetGuildRoleSubscriptionTierDetails::class.java, nextAction = next),
            wizardStep(
                WidgetGuildRoleSubscriptionTierBenefits::class.java,
                arguments = benefitArguments(GuildRoleSubscriptionTierBenefitListType.CHANNEL),
                nextAction = next,
            ),
            wizardStep(
                WidgetGuildRoleSubscriptionTierBenefits::class.java,
                arguments = benefitArguments(GuildRoleSubscriptionTierBenefitListType.INTANGIBLE),
                nextAction = next,
            ),
            wizardStep(WidgetGuildRoleSubscriptionTierDesign::class.java, nextAction = next),
            StepsView.FragmentStep(
                fragment = WidgetGuildRoleSubscriptionReview::class.java,
                doneLabel = R.string.guild_role_subscription_plan_finish,
                arguments = bundleOf(EXTRA_GUILD_ID to guildId),
                doneAction = { createTierViewModel.submit() },
                cancelAllowed = false,
                scrollingAllowed = false,
            ),
        )
        stepsView.setup(StepsView.PagerAdapter(this, steps), onClose = { requireActivity().finish() })
        stepsView.setStepProgressIndicatorVisible(true)
        setOnBackPressed({
            createTierViewModel.goToPreviousStep()
            true
        })
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        tierViewModel.observeViewState()
            .distinctUntilChanged()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetCreateGuildRoleSubscriptionTier::class.java) {
                createTierViewModel.onTierUpdated(it.guildRoleSubscriptionTier)
            }
        createTierViewModel.observeViewState()
            .distinctUntilChanged()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetCreateGuildRoleSubscriptionTier::class.java, onNext = ::configureUI)
        createTierViewModel.observeEvents()
            .distinctUntilChanged()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetCreateGuildRoleSubscriptionTier::class.java, onNext = ::handleEvent)
    }

    private fun configureUI(viewState: CreateGuildRoleSubscriptionTierViewModel.ViewState) {
        if (viewState.currentStep < 0) {
            requireActivity().finish()
            return
        }
        stepsView.setCurrentPage(viewState.currentStep)
        stepsView.setIsNextButtonEnabled(viewState.canProceed)
        stepsView.setIsDoneButtonEnabled(!viewState.isSubmitting)
        stepsView.setIsLoading(viewState.isSubmitting)
    }

    private fun handleEvent(event: CreateGuildRoleSubscriptionTierViewModel.Event) {
        when (event) {
            CreateGuildRoleSubscriptionTierViewModel.Event.SubmitSuccess -> activity?.finish()
            is CreateGuildRoleSubscriptionTierViewModel.Event.SubmitFailure ->
                event.error.showToasts(requireContext())
            is CreateGuildRoleSubscriptionTierViewModel.Event.ServerGatingUpdate ->
                tierViewModel.setFullServerGating(event.isFullServerGating)
        }
    }

    private fun benefitArguments(type: GuildRoleSubscriptionTierBenefitListType) = bundleOf(
        EXTRA_GUILD_ID to guildId,
        WidgetGuildRoleSubscriptionTierBenefits.INTENT_EXTRA_BENEFIT_LIST_TYPE to type,
    )

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
        internal const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        internal const val INTENT_EXTRA_GROUP_LISTING_ID = "INTENT_EXTRA_GROUP_LISTING_ID"
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"

        fun launch(context: Context, guildId: Long, groupListingId: Long) {
            AppScreenRouter.launch(
                context,
                WidgetCreateGuildRoleSubscriptionTier::class.java,
                Intent()
                    .putExtra(INTENT_EXTRA_GUILD_ID, guildId)
                    .putExtra(INTENT_EXTRA_GROUP_LISTING_ID, groupListingId),
            )
        }
    }
}
