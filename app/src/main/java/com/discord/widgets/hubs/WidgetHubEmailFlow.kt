package com.discord.widgets.hubs

import android.app.Activity
import android.content.Intent
import android.text.SpannableStringBuilder
import android.view.View
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appViewModels
import com.discord.dialogs.SimpleConfirmationDialog
import com.discord.dialogs.SimpleConfirmationDialogArgs
import com.discord.i18n.I18nSpans
import com.discord.i18n.i18nPlural
import com.discord.i18n.withClickableHook
import com.discord.stores.StoreStream
import com.discord.stores.utilities.Loading
import com.discord.stores.utilities.Success
import com.discord.stores.utilities.handleResponse
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.utilities.view.validators.ValidationManager
import com.discord.views.LoadingButton
import com.discord.widgets.nux.CreateGuildTrigger
import com.discord.widgets.nux.WidgetGuildTemplates
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * The school-email prompt: "connect your school email and we'll find your Student Hub".
 *
 * Binds `R.layout.widget_hub_email_flow`, which stacks three `<include>`d pages behind one button
 * pair and shows exactly one of them:
 *  - `widget_discord_hub_email_input` while nothing has been submitted,
 *  - `widget_hub_email_flow_confirmation` once the lookup matched a hub ("check your email"),
 *  - `widget_hub_email_flow_waitlist` once it matched none, or the waitlist was joined.
 *
 * When several hubs match, the flow starts [WidgetHubDomains] for a result instead; the result is
 * either a waitlist signup (which switches this screen to the waitlist page) or a completed
 * verification (which selects the hub and closes the flow).
 */
class WidgetHubEmailFlow : AppFragment(R.layout.widget_hub_email_flow) {

    private val args: HubEmailArgs by hubArgs()

    private val viewModel: WidgetHubEmailViewModel by appViewModels { WidgetHubEmailViewModel() }

    private lateinit var inputPage: View
    private lateinit var confirmationPage: View
    private lateinit var waitlistPage: View
    private lateinit var yesButton: LoadingButton
    private lateinit var noButton: MaterialButton
    private lateinit var emailLayout: TextInputLayout
    private lateinit var emailInput: TextInputEditText
    private lateinit var emailLabel: TextView
    private lateinit var headerTitle: TextView
    private lateinit var headerDescription: LinkifiedTextView
    private lateinit var confirmationTitle: TextView
    private lateinit var waitlistDescription: TextView

    private val validationManager: ValidationManager by lazy { createEmailValidationManager(emailLayout) }

    private val email: String
        get() = emailInput.text?.toString().orEmpty()

    /**
     * Receives [MultipleDomainResult] from the screens this flow pushes.
     *
     * Registered eagerly (a fragment must register before `STARTED`), and it is the only place the
     * flow learns what happened inside those screens.
     */
    private val activityResultHandler: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            when (val domainResult = result.data?.getArgsFromIntent<MultipleDomainResult>()) {
                is HubWaitlistResult -> viewModel.setHubWaitlistResult(domainResult)
                is AuthenticationResult -> {
                    StoreStream.selectGuild(domainResult.guildId)
                    activity?.finish()
                }
                null -> Unit
                else -> activity?.finish()
            }
        }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        inputPage = view.findViewById(R.id.discord_hub_email_input)
        confirmationPage = view.findViewById(R.id.discord_hub_email_confirmation)
        waitlistPage = view.findViewById(R.id.discord_hub_waitlist)
        yesButton = view.findViewById(R.id.discord_hub_email_yes)
        noButton = view.findViewById(R.id.discord_hub_email_no)
        emailLayout = view.findViewById(R.id.discord_hub_email_header_description_email_input_layout)
        emailInput = view.findViewById(R.id.discord_hub_email_header_description_email_input)
        emailLabel = view.findViewById(R.id.discord_hub_email_label)
        headerTitle = view.findViewById(R.id.discord_hub_email_header_title)
        headerDescription = view.findViewById(R.id.discord_hub_email_header_description)
        confirmationTitle = view.findViewById(R.id.discord_hub_email_confirmation_header_title)
        waitlistDescription = view.findViewById(R.id.hub_waitlist_description)

        view.findViewById<View>(R.id.discord_hub_email_confirmation_resend).setOnClickListener { verifyEmail() }
        view.findViewById<View>(R.id.discord_hub_email_confirmation_different).setOnClickListener { viewModel.reset() }
        noButton.setOnClickListener { onDeclined() }

        headerTitle.text = headerTitleText(view)
        headerDescription.text = headerDescriptionText()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .distinctUntilChanged()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetHubEmailFlow::class.java) { updateView(it) }
    }

    private fun headerTitleText(view: View): CharSequence = when (args.entryPoint) {
        HubEmailEntryPoint.Default -> getString(R.string.hub_email_connection_nux_header)
        HubEmailEntryPoint.Invite -> I18nSpans.render(
            getString(R.string.hub_email_connection_invite_header),
            "count" to view.context.i18nPlural(
                R.plurals.hub_email_connection_invite_header_count,
                args.guildMemberCount,
                "count" to args.guildMemberCount,
            ),
            "guildName" to args.guildName,
        )
        HubEmailEntryPoint.Onboarding -> getString(R.string.hub_waitlist_modal_join_header)
    }

    private fun headerDescriptionText(): CharSequence {
        val description = getString(
            when (args.entryPoint) {
                HubEmailEntryPoint.Default -> R.string.hub_email_connection_nux_content_description
                HubEmailEntryPoint.Invite -> R.string.hub_email_connection_content_description
                HubEmailEntryPoint.Onboarding -> R.string.hub_waitlist_modal_join_subheader
            },
        )
        val learnMore = I18nSpans.render(getString(R.string.learn_more_link))
            .withClickableHook(LEARN_MORE_HOOK) { showLearnMore() }
        return SpannableStringBuilder(description).append(" ").append(learnMore)
    }

    /**
     * The reference opens a `NoticeBottomSheet` here. That sheet has not been reconstructed, so the
     * same two strings are shown in the confirmation dialog this project does have.
     */
    private fun showLearnMore() {
        SimpleConfirmationDialog.show(
            fragmentManager = childFragmentManager,
            args = SimpleConfirmationDialogArgs(
                title = getString(R.string.hub_email_connection_sidebar_learn_more_header),
                description = getString(R.string.hub_email_connection_sidebar_description),
                positiveButtonText = getString(R.string.okay),
            ),
            positiveClickListener = { },
        )
    }

    private fun onDeclined() {
        val activity = activity ?: return
        if (args.entryPoint == HubEmailEntryPoint.Onboarding) {
            StoreStream.getNux().updateNux { it.copy(postRegister = false) }
            WidgetGuildTemplates.launch(activity, CreateGuildTrigger.NUF, isNux = true)
        }
        activity.finish()
    }

    private fun updateView(state: HubEmailState) {
        val context = context ?: return
        val verification = state.verifyEmailAsync()
        val showConfirmation = verification?.hasMatchingGuild == true
        val showInput = state.verifyEmailAsync !is Success<*> && state.waitlistResult == null
        val showWaitlist = !((verification == null || verification.hasMatchingGuild) && state.waitlistResult == null)
        val submittedEmail = email

        inputPage.visibility = if (showInput) View.VISIBLE else View.GONE
        noButton.visibility = if (showInput) View.VISIBLE else View.GONE
        confirmationPage.visibility = if (showConfirmation) View.VISIBLE else View.GONE
        waitlistPage.visibility = if (showWaitlist) View.VISIBLE else View.GONE

        confirmationTitle.text = I18nSpans.render(
            getString(R.string.member_verification_email_confirmation_title),
            "email" to submittedEmail,
        )
        state.waitlistResult?.school?.let { school ->
            waitlistDescription.text =
                I18nSpans.render(getString(R.string.hub_waitlist_modal_joined_description), "school" to school)
        }

        yesButton.setOnClickListener { if (showWaitlist) activity?.finish() else verifyEmail() }
        yesButton.visibility = if (showConfirmation) View.GONE else View.VISIBLE
        yesButton.setText(
            getString(
                if (showWaitlist) R.string.hub_add_server_confirmation_button
                else R.string.hub_email_connection_content_button,
            ),
        )
        yesButton.setIsLoading(state.verifyEmailAsync is Loading)

        state.verifyEmailAsync.handleResponse(context) { success ->
            if (state.hasMultipleDomains) {
                AppScreenRouter.launchForResult(
                    context,
                    activityResultHandler,
                    WidgetHubDomains::class.java,
                    HubDomainArgs(submittedEmail, success.response.guildsInfo.map(DomainGuildInfo::from)).toArgsIntent(),
                )
                viewModel.reset()
            } else if (!success.response.hasMatchingGuild) {
                AppScreenRouter.launchForResult(
                    context,
                    activityResultHandler,
                    WidgetHubWaitlist::class.java,
                    HubWaitlistArgs(submittedEmail).toArgsIntent(),
                )
                viewModel.reset()
            }
            storeDirectories().markDiscordHubClicked()
        }
    }

    private fun verifyEmail() {
        val valid = validationManager.validate()
        // The "we'll only use this to find your hub" note is hidden while the field is rejected, so
        // the error message is not competing with it for the same slot.
        emailLabel.visibility = if (valid) View.VISIBLE else View.GONE
        if (valid) {
            viewModel.submitEmail(email)
            hideKeyboard(view)
        }
    }

    companion object {
        /** Fragment class name, used by the routes that open this screen by name. */
        @JvmField
        val NAME: String = WidgetHubEmailFlow::class.java.name

        private const val LEARN_MORE_HOOK = "{helpdeskArticle}"

    }
}
