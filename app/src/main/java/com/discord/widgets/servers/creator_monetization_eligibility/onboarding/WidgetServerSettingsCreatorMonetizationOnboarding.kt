package com.discord.widgets.servers.creator_monetization_eligibility.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewStub
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.text.HtmlCompat
import com.discord.R
import com.discord.api.creatormonetization.CreatorMonetizationEligibilityRequirements
import com.discord.api.creatormonetization.CreatorMonetizationEnableRequest
import com.discord.api.guild.GuildFeature
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.app.AppViewFlipper
import com.discord.app.appViewModels
import com.discord.utilities.resources.DurationUnit
import com.discord.utilities.resources.MillisecondsFormatter.formatDurationInMillis
import com.discord.utilities.resources.formatDuration
import com.discord.utilities.rx.ui
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.views.LoadingButton
import com.discord.widgets.servers.creator_monetization_eligibility.EligibilityChecklistView
import com.discord.widgets.settings.SettingsNavigation
import com.google.android.material.button.MaterialButton
import com.discord.i18n.I18nSpans

class WidgetServerSettingsCreatorMonetizationOnboarding :
    AppFragment(R.layout.widget_server_settings_creator_monetization_onboarding) {

    private val guildId: Long
        get() = arguments?.getLong(EXTRA_GUILD_ID, INVALID_GUILD_ID)
            ?.takeIf { it != INVALID_GUILD_ID }
            ?: mostRecentIntent.getLongExtra(EXTRA_GUILD_ID, INVALID_GUILD_ID)

    private val viewModel: ServerSettingsCreatorMonetizationOnboardingViewModel by appViewModels {
        ServerSettingsCreatorMonetizationOnboardingViewModel(guildId)
    }

    private lateinit var flipper: AppViewFlipper
    private var createRequestView: View? = null
    private var acceptTermsView: View? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        flipper = view.findViewById(R.id.view_flipper)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.guild_role_subscriptions_channel_label)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().distinctUntilChanged().ui(this).subscribe(::configureUI)
        viewModel.listenForEvents().ui(this).subscribe {
            AppToast.show(this, R.string.default_failure_to_perform_action_message)
        }
    }

    private fun configureUI(state: ServerSettingsCreatorMonetizationOnboardingViewModel.ViewState) {
        when (state) {
            ServerSettingsCreatorMonetizationOnboardingViewModel.ViewState.Loading ->
                flipper.displayedChild = LOADING
            is ServerSettingsCreatorMonetizationOnboardingViewModel.ViewState.Error -> {
                flipper.displayedChild = ERROR
                requireView().findViewById<TextView>(R.id.error_notice_text).text =
                    state.error.displayString(requireContext())
            }
            is ServerSettingsCreatorMonetizationOnboardingViewModel.ViewState.CreateRequest -> {
                flipper.displayedChild = CREATE_REQUEST
                configureCreateRequest(state)
            }
            is ServerSettingsCreatorMonetizationOnboardingViewModel.ViewState.AcceptTerms -> {
                if (state.guild.hasFeature(GuildFeature.CREATOR_MONETIZABLE)) {
                    requireActivity().finish()
                } else {
                    flipper.displayedChild = ACCEPT_TERMS
                    configureAcceptTerms(state)
                }
            }
        }
    }

    private fun configureCreateRequest(
        state: ServerSettingsCreatorMonetizationOnboardingViewModel.ViewState.CreateRequest,
    ) {
        val root = createRequestView ?: requireView()
            .findViewById<ViewStub>(R.id.onboarding_create_request)
            .inflate()
            .also { createRequestView = it }
        root.findViewById<LoadingButton>(R.id.apply_button).apply {
            isEnabled = state.requirements.canApply && state.isGuildOwner
            visibility = if (state.isApplicationPending) View.GONE else View.VISIBLE
            setIsLoading(state.isSubmitting)
            setOnClickListener { viewModel.createRequestToEnableMonetization() }
        }
        root.findViewById<MaterialButton>(R.id.application_pending_button).visibility =
            if (state.isApplicationPending) View.VISIBLE else View.GONE
        ownerNotice(root, state.isGuildOwner)

        root.findViewById<LinkifiedTextView>(R.id.requirements_description).text = markdown(
            getString(R.string.guild_role_subscription_settings_enable_requirements_description)
                .replace("{faqUrl}", FAQ_URL),
        )
        root.findViewById<ImageView>(R.id.eligibility_checklist_header_indicator).isActivated =
            state.requirements.sufficient
        root.findViewById<TextView>(R.id.eligibility_checklist_title).text = displayTemplate(
            getString(
                if (state.requirements.sufficient) {
                    R.string.guild_role_subscription_settings_eligible_message
                } else {
                    R.string.guild_role_subscription_settings_not_eligible_message
                },
            ),
        )
        root.findViewById<EligibilityChecklistView>(R.id.eligibility_checklist)
            .bind(checklist(state.requirements))

        val rejection = state.requirements.rejection
        val cooldown = rejection?.canReapplyAt?.time?.minus(System.currentTimeMillis())
            ?.takeIf { it > 0 }
        root.findViewById<TextView>(R.id.request_rejected_notice).apply {
            visibility = if (
                cooldown != null &&
                state.requirements.latestRequest?.state == CreatorMonetizationEnableRequest.State.REJECTED
            ) View.VISIBLE else View.GONE
            if (cooldown != null) {
                text = getString(R.string.guild_role_subscription_settings_enable_request_rejected)
                    .replace(
                        "{requestCooldownDuration}",
                        requireContext().formatDurationInMillis(cooldown).toString(),
                    )
            }
        }
    }

    private fun configureAcceptTerms(
        state: ServerSettingsCreatorMonetizationOnboardingViewModel.ViewState.AcceptTerms,
    ) {
        val root = acceptTermsView ?: requireView()
            .findViewById<ViewStub>(R.id.onboarding_accept_terms)
            .inflate()
            .also { acceptTermsView = it }
        ownerNotice(root, state.isGuildOwner)
        root.findViewById<CheckBox>(R.id.accept_terms_checkbox).apply {
            setOnCheckedChangeListener(null)
            isChecked = state.hasAcceptedTerms
            isEnabled = !state.isSubmitting && state.isGuildOwner
            setOnCheckedChangeListener { _, checked -> viewModel.setHasAcceptedTerms(checked) }
        }
        root.findViewById<LoadingButton>(R.id.accept_terms_button).apply {
            isEnabled = state.hasAcceptedTerms && state.isGuildOwner
            setIsLoading(state.isSubmitting)
            setOnClickListener { viewModel.submitTermsAcceptance() }
        }
    }

    private fun ownerNotice(root: View, isGuildOwner: Boolean) {
        val existing = root.findViewById<View>(R.id.only_guild_owner_notice)
        val notice = if (!isGuildOwner && existing is ViewStub) existing.inflate() else existing
        if (notice !is ViewStub) notice.visibility = if (isGuildOwner) View.GONE else View.VISIBLE
    }

    private fun checklist(requirements: CreatorMonetizationEligibilityRequirements) = listOf(
        requirement(
            requirements.size,
            R.string.guild_role_subscription_settings_eligibility_requirement_members_eligible,
            R.string.guild_role_subscription_settings_eligibility_requirement_members_not_eligible,
            getString(R.string.guild_role_subscription_settings_eligibility_requirement_members_description)
                .replace("{minimumSize}", requirements.minimumSize.toString()),
        ),
        requirement(
            requirements.age,
            R.string.guild_role_subscription_settings_eligibility_requirement_age_eligible,
            R.string.guild_role_subscription_settings_eligibility_requirement_age_not_eligible,
            getString(R.string.guild_role_subscription_settings_eligibility_requirement_age_description)
                .replace("{minimumAge}", formatDays(requirements.minimumAgeInDays).toString()),
        ),
        requirement(
            requirements.safeEnvironment,
            R.string.guild_role_subscription_settings_eligibility_requirement_violations_eligible,
            R.string.guild_role_subscription_settings_eligibility_requirement_violations_not_eligible,
            markdown(
                getString(R.string.guild_role_subscription_settings_eligibility_requirement_violations_description)
                    .replace("{communityGuidelinesUrl}", COMMUNITY_GUIDELINES_URL),
            ),
        ),
        requirement(
            requirements.engagementHealthy,
            R.string.guild_role_subscription_settings_eligibility_requirement_communicators_eligible,
            R.string.guild_role_subscription_settings_eligibility_requirement_communicators_not_eligible,
            getString(R.string.guild_role_subscription_settings_eligibility_requirement_communicators_description),
        ),
        requirement(
            requirements.retentionHealthy,
            R.string.guild_role_subscription_settings_eligibility_requirement_retention_eligible,
            R.string.guild_role_subscription_settings_eligibility_requirement_retention_not_eligible,
            getString(R.string.guild_role_subscription_settings_eligibility_requirement_retention_description),
        ),
        requirement(
            requirements.nsfwProperties.isEmpty(),
            R.string.guild_role_subscription_settings_eligibility_requirement_nsfw_content_eligible,
            R.string.guild_role_subscription_settings_eligibility_requirement_nsfw_content_not_eligible,
            getString(R.string.guild_role_subscription_settings_eligibility_requirement_nsfw_content_description),
        ),
    )

    private fun requirement(met: Boolean, metTitle: Int, unmetTitle: Int, description: CharSequence) =
        EligibilityChecklistView.Requirement(getString(if (met) metTitle else unmetTitle), description, met)

    private fun formatDays(days: Int): CharSequence = if (days > 0 && days % 7 == 0) {
        requireContext().formatDuration(DurationUnit.WEEKS, days / 7)
    } else {
        requireContext().formatDuration(DurationUnit.DAYS, days)
    }

    private fun markdown(text: String): CharSequence = HtmlCompat.fromHtml(
        text.replace(MARKDOWN_LINK, "<a href=\"$2\">$1</a>"),
        HtmlCompat.FROM_HTML_MODE_LEGACY,
    )

    /** Unwraps the hook label and renders the emphasis, rather than dropping both. */
    private fun displayTemplate(text: String): CharSequence = I18nSpans.render(text.replace(HOOK, "$1"))

    companion object {
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val INVALID_GUILD_ID = -1L
        private const val LOADING = 0
        private const val ERROR = 1
        private const val CREATE_REQUEST = 2
        private const val ACCEPT_TERMS = 3
        private const val FAQ_URL = "https://support.discord.com/hc/en-us/articles/5371495812631"
        private const val COMMUNITY_GUIDELINES_URL =
            "https://support.discord.com/hc/en-us/articles/360035969312"
        private val MARKDOWN_LINK = Regex("\\[([^]]+)]\\(([^)]+)\\)")
        private val HOOK = Regex("\\$\\[([^]]+)]\\([^)]+\\)")

        fun newInstance(guildId: Long) = WidgetServerSettingsCreatorMonetizationOnboarding().apply {
            arguments = Bundle().apply { putLong(EXTRA_GUILD_ID, guildId) }
        }

        fun launch(context: Context, guildId: Long) {
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsCreatorMonetizationOnboarding::class.java,
                Intent().putExtra(EXTRA_GUILD_ID, guildId),
            )
        }
    }
}
