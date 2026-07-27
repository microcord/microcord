package com.discord.widgets.user.email

import android.content.Context
import android.content.Intent
import android.util.Patterns
import android.view.View
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.restapi.RestAPIParams
import com.discord.utilities.dimmer.DimmerView
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.rx.withDimmer
import com.discord.utilities.view.extensions.addBindedTextWatcher
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.widgets.user.account.WidgetUserAccountVerifyBase
import com.discord.widgets.user.account.WidgetUserAccountVerifyBase.Mode
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout

/**
 * Changes the address on the account, which is how a user whose e-mail is wrong (or no longer
 * theirs) escapes an e-mail verification gate.
 *
 * Changing an address is a credentialed operation, so the current password is required in the same
 * form; the submit button stays disabled until the address parses and a plausible password has been
 * typed, which is the only client-side validation the reference does here.
 *
 * ### Secrets
 * The password is read straight from the field at submit time and handed to `PATCH users/@me`. It
 * is never stored in a property, never written to the fragment's saved state, and
 * `RestAPIParams.UserInfo.toString()` renders as `UserInfo(<redacted>)`, so it cannot reach a log
 * or a crash report through the request body either.
 */
class WidgetUserEmailUpdate : WidgetUserAccountVerifyBase(R.layout.widget_user_email_update) {

    private lateinit var emailInput: TextInputLayout
    private lateinit var passwordInput: TextInputLayout
    private lateinit var submitButton: MaterialButton
    private lateinit var dimmer: DimmerView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()

        emailInput = view.findViewById(R.id.alert_verify_email_change_email)
        passwordInput = view.findViewById(R.id.alert_verify_email_change_password)
        submitButton = view.findViewById(R.id.alert_verify_email_change)
        dimmer = view.findViewById(R.id.dimmer_view)

        emailInput.addBindedTextWatcher(this) { handleTextChanged() }
        passwordInput.addBindedTextWatcher(this) { handleTextChanged() }
        handleTextChanged()

        submitButton.setOnClickListener { updateEmail() }
    }

    private fun handleTextChanged() {
        val hasPlausiblePassword = passwordInput.getTextOrEmpty().length >= MIN_PASSWORD_LENGTH_FOR_SUBMIT
        val hasValidEmail = Patterns.EMAIL_ADDRESS.matcher(emailInput.getTextOrEmpty()).matches()
        submitButton.isEnabled = hasPlausiblePassword && hasValidEmail
    }

    private fun updateEmail() {
        RestAPI.api
            .patchUser(
                RestAPIParams.UserInfo.createForEmail(
                    emailInput.getTextOrEmpty(),
                    passwordInput.getTextOrEmpty(),
                ),
            )
            .restSubscribeOn()
            .withDimmer(dimmer)
            .ui(this)
            .appSubscribe(WidgetUserEmailUpdate::class.java, context) { onEmailUpdated() }
    }

    /**
     * Reports success to whoever launched the screen. `WidgetUserEmailVerify` uses that result to
     * close itself too, so the user is not left staring at a "verify your old address" screen.
     */
    private fun onEmailUpdated() {
        appActivity?.setResult(android.app.Activity.RESULT_OK)
        appActivity?.finish()
    }

    companion object {
        /**
         * Not a password policy — the API owns that. It only keeps the button from being tappable
         * when the field is obviously empty or half-typed. Matches the reference's `>= 3`.
         */
        private const val MIN_PASSWORD_LENGTH_FOR_SUBMIT = 3

        @JvmStatic
        @JvmOverloads
        fun launch(context: Context, mode: Mode, launcher: ActivityResultLauncher<Intent>? = null) {
            val intent = WidgetUserAccountVerifyBase.getLaunchIntent(
                mode,
                phoneAllowed = false,
                emailAllowed = true,
            )
            if (launcher != null) {
                AppScreenRouter.launchForResult(context, launcher, WidgetUserEmailUpdate::class.java, intent)
            } else {
                AppScreenRouter.launch(context, WidgetUserEmailUpdate::class.java, intent)
            }
        }

        /** Registers a launcher that runs [callback] only when the address was actually changed. */
        @JvmStatic
        fun registerForResult(fragment: AppFragment, callback: () -> Unit): ActivityResultLauncher<Intent> =
            fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
                if (result.resultCode == android.app.Activity.RESULT_OK) callback()
            }
    }
}
