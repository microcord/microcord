package com.discord.widgets.settings.account

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.models.user.MeUser
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.dimmer.DimmerView
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.rx.withDimmer
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.extensions.setOnImeActionDone
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.widgets.settings.SettingsStrings
import com.discord.widgets.user.WidgetUserPasswordVerify
import com.google.android.material.textfield.TextInputLayout

class WidgetSettingsAccountEmailEditConfirm :
    AppFragment(R.layout.widget_settings_account_email_edit_confirm) {

    private lateinit var description: TextView
    private lateinit var code: TextInputLayout
    private lateinit var resend: LinkifiedTextView
    private lateinit var next: Button
    private lateinit var dimmer: DimmerView
    private val passwordVerifyLauncher: ActivityResultLauncher<Intent> =
        WidgetUserPasswordVerify.registerForResult(this) {
            activity?.setResult(Activity.RESULT_OK)
            @Suppress("DEPRECATION")
            activity?.onBackPressed()
        }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarSubtitle(R.string.user_settings)
        setActionBarTitle(R.string.user_settings_account_change_email_confirm_title)
        setActionBarDisplayHomeAsUpEnabled()

        description = view.findViewById(R.id.edit_account_email_confirm_description)
        code = view.findViewById(R.id.edit_account_code_wrap)
        resend = view.findViewById(R.id.settings_account_confirm_resend)
        next = view.findViewById(R.id.settings_account_next)
        dimmer = view.findViewById(R.id.dimmer_view)
        code.setOnImeActionDone { submitVerificationCode() }
        next.setOnClickListener { submitVerificationCode() }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        StoreStream.getUsers().observeMe()
            .ui(this)
            .appSubscribe(WidgetSettingsAccountEmailEditConfirm::class.java) { configureUI(it) }
    }

    private fun configureUI(me: MeUser) {
        description.text = SettingsStrings.render(
            requireContext(),
            R.string.user_settings_account_change_email_confirm_description,
            "oldEmail" to me.email.orEmpty(),
        )
        resend.text = SettingsStrings.render(
            requireContext(),
            R.string.user_settings_account_change_email_confirm_resend,
            onLink = { if (it == RESEND_HOOK) resendVerificationCode() },
        )
    }

    private fun resendVerificationCode() {
        RestAPI.api.userEmail()
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetSettingsAccountEmailEditConfirm::class.java, context) {
                AppToast.show(this, R.string.user_settings_account_change_email_confirm_code_sent)
            }
    }

    private fun submitVerificationCode() {
        RestAPI.api.userEmailVerifyCode(RestAPIParams.UserEmailConfirmCode(code.getTextOrEmpty()))
            .restSubscribeOn()
            .withDimmer(dimmer)
            .ui(this)
            .appSubscribe(WidgetSettingsAccountEmailEditConfirm::class.java, context) { response ->
                WidgetUserPasswordVerify.launchUpdateAccountSettings(
                    requireContext(),
                    passwordVerifyLauncher,
                    email = requestedEmail(),
                    emailToken = response.token,
                )
            }
    }

    private fun requestedEmail(): String? =
        arguments?.getString(EXTRA_EMAIL) ?: mostRecentIntent.getStringExtra(EXTRA_EMAIL)

    companion object {
        private const val EXTRA_EMAIL = "extra_email"
        private const val RESEND_HOOK = "onResend"

        @JvmStatic
        fun launch(
            context: Context,
            launcher: ActivityResultLauncher<Intent>,
            email: String? = null,
        ) {
            AppScreenRouter.launchForResult(
                context,
                launcher,
                WidgetSettingsAccountEmailEditConfirm::class.java,
                Intent().putExtra(EXTRA_EMAIL, email),
            )
        }

        @JvmStatic
        fun registerForResult(
            fragment: AppFragment,
            callback: () -> Unit,
        ): ActivityResultLauncher<Intent> =
            fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    result: ActivityResult ->
                if (result.resultCode == Activity.RESULT_OK) callback()
            }
    }
}
