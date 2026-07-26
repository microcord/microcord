package com.discord.widgets.hubs

import android.app.Activity
import android.view.View
import android.widget.Toast
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.appViewModels
import com.discord.i18n.I18nSpans
import com.discord.stores.utilities.handleResponse
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.view.ToastManager
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.views.CodeVerificationView

/**
 * "Enter your PIN" — redeems the code Discord emailed to the school address.
 *
 * Binds `R.layout.widget_hub_authentication`. On success the screen finishes with
 * [AuthenticationResult] so the caller can select the hub the user just joined.
 */
class WidgetHubAuthentication : AppFragment(R.layout.widget_hub_authentication) {

    private val args: HubAuthenticationArgs by hubArgs()

    private val viewModel: WidgetHubAuthenticationViewModel by appViewModels {
        WidgetHubAuthenticationViewModel(args.email, args.guildId)
    }

    private val toastManager = ToastManager()

    private lateinit var description: LinkifiedTextView
    private lateinit var verification: CodeVerificationView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        description = view.findViewById(R.id.description)
        verification = view.findViewById(R.id.verification)

        verification.onCodeEntered = { code -> viewModel.verifyCode(code) }
        // "Didn't get the code? [Resend code](onClick) to !!{email}!!"
        description.text = I18nSpans
            .render(getString(R.string.hub_pin_description), "email" to args.email)
            .withHubHook(RESEND_HOOK) { viewModel.resendEmail() }
        showKeyboard(verification)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetHubAuthentication::class.java) { configureUi(it) }
    }

    override fun onDestroyView() {
        toastManager.close()
        super.onDestroyView()
    }

    private fun configureUi(state: HubAuthenticationState) {
        val context = context ?: return

        state.authenticationAsync.handleResponse(context) { success ->
            hideKeyboard(view)
            val result = success.response
            if (result.joined) {
                activity?.let { activity ->
                    activity.setResult(Activity.RESULT_OK, AuthenticationResult(result.guild.id).toArgsIntent())
                    activity.finish()
                }
                // The reference also clears the NUX "post-registration" flag here through
                // StoreNux.updateNux { it.copy(postRegistrationJoin = false) }. StoreNux does not exist
                // in this project; recorded as a gap rather than approximated.
            } else {
                // A well-formed code that did not join the user to anything.
                Toast.makeText(context, R.string.error_generic_title, Toast.LENGTH_SHORT).show()
            }
        }

        state.resendEmailAsync.handleResponse(context) {
            toastManager.show(context, R.string.member_verification_email_verification_email_sent)
        }
    }

    private companion object {
        const val RESEND_HOOK = "onClick"
    }
}
