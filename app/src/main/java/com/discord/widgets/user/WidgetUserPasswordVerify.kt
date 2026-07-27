package com.discord.widgets.user

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.dimmer.DimmerView
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.rx.withDimmer
import com.discord.utilities.view.extensions.addBindedTextWatcher
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.extensions.setOnImeActionDone
import com.discord.utilities.view.validators.BasicTextInputValidator
import com.discord.utilities.view.validators.Input
import com.discord.utilities.view.validators.ValidationManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout
import rx.Observable

/**
 * The password re-authentication gate.
 *
 * Every account change that a stolen session could otherwise make silently — a new e-mail address,
 * a new username, adding or removing the phone number — funnels through here, and the [Action] on
 * the launch intent says which request the entered password should be spent on. That is why the
 * screen has no "what am I confirming?" state of its own: whoever launched it already showed the
 * user what they are about to change.
 *
 * ### Secrets — how the password is kept out of logs and saved state
 * - The field is a password field in the recovered layout, so the platform excludes it from
 *   autofill save and from `View.onSaveInstanceState`.
 * - The value is **never** assigned to a property. Every use reads it from the field at the moment
 *   of the request and passes it directly into the request body.
 * - No `onSaveInstanceState` is implemented, and nothing is written to the arguments bundle, so the
 *   password cannot survive a process death.
 * - Every body it enters redacts itself: `RestAPIParams.UserInfo` renders `UserInfo(<redacted>)`,
 *   `DeletePhone` renders `DeletePhone(<redacted>)`, and `VerificationPhoneCode` keeps only its
 *   non-secret `source`. So neither a crash report nor an error log can carry it.
 * - The single-use phone token and e-mail token arrive as intent extras and are likewise read on
 *   demand rather than cached.
 *
 * ### Divergence from the reference
 * The reference drives the save button with `utilities.stateful.StatefulViews`, which keeps a map of
 * every watched view's current *and* original value so it can show the button only once something
 * changed. That utility does not exist in this reconstruction, and holding the password in such a
 * map would violate the project's secret-handling contract. The observable behaviour for a
 * single-field form is reproduced directly — the button appears once the field is non-empty — with
 * no copy of the value retained anywhere.
 */
class WidgetUserPasswordVerify : AppFragment(R.layout.widget_user_password_verify) {

    private lateinit var passwordInput: TextInputLayout
    private lateinit var saveButton: FloatingActionButton
    private lateinit var dimmer: DimmerView

    private val validationManager: ValidationManager by lazy {
        ValidationManager(
            Input.TextInputLayoutInput(
                FIELD_PASSWORD,
                passwordInput,
                BasicTextInputValidator.createRequiredInputValidator(R.string.password_required),
                // Reference `AuthUtils.createPasswordInputValidator`; `utilities/auth` does not
                // exist here, so its 6..128 rule is stated inline rather than invented elsewhere.
                BasicTextInputValidator(R.string.password_length_error) { it.length in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH },
            ),
        )
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        passwordInput = view.findViewById(R.id.edit_account_password_wrap)
        saveButton = view.findViewById(R.id.settings_account_save)
        dimmer = view.findViewById(R.id.dimmer_view)

        setActionBarSubtitle(R.string.user_settings)
        setActionBarTitle(R.string.user_settings_account_verify_password_title_in_title_case)
        setActionBarDisplayHomeAsUpEnabled()

        // Only "is it empty" is read here; the value itself is not captured.
        passwordInput.addBindedTextWatcher(this) { editable ->
            if (editable.isEmpty()) saveButton.hide() else saveButton.show()
        }
        if (passwordInput.getTextOrEmpty().isEmpty()) saveButton.hide()

        passwordInput.setOnImeActionDone { saveInfo() }
        saveButton.setOnClickListener { saveInfo() }
    }

    private fun saveInfo() {
        if (!validationManager.validate()) return
        hideKeyboard()
        val password = passwordInput.getTextOrEmpty()
        when (action()) {
            Action.UpdateAccountInfo -> updateAccountInfo(password)
            Action.RemovePhoneNumber -> removePhoneNumber(password)
            Action.UpdatePhoneNumber -> updatePhoneNumber(password)
            null -> Unit
        }
    }

    @Suppress("DEPRECATION")
    private fun action(): Action? =
        mostRecentIntent.getSerializableExtra(INTENT_EXTRA_ACTION) as? Action

    private fun updateAccountInfo(password: String) {
        RestAPI.api
            .patchUser(
                RestAPIParams.UserInfo(
                    email = mostRecentIntent.getStringExtra(INTENT_EXTRA_EMAIL),
                    emailToken = mostRecentIntent.getStringExtra(INTENT_EXTRA_EMAIL_TOKEN),
                    password = password,
                    username = mostRecentIntent.getStringExtra(INTENT_EXTRA_USERNAME),
                    pushToken = StoreStream.getNotifications().getPushToken(),
                    discriminator = mostRecentIntent.getStringExtra(INTENT_EXTRA_DISCRIMINATOR),
                ),
            )
            .submit { updatedUser ->
                // The response carries a rotated token: changing credentials invalidates the old
                // one, so the session has to be re-authed before anything else uses it.
                StoreStream.getAuthentication().setAuthed(updatedUser.token)
                // Reference also calls `GoogleSmartLockManager.updateAccountInfo(newEmail,
                // password)` here so the saved credential follows the change. `utilities/auth` and
                // the Smart Lock dependency do not exist in this reconstruction; a stale saved
                // credential is the visible consequence, recorded in the handoff report.
                finishWithSuccess()
            }
    }

    private fun removePhoneNumber(password: String) {
        RestAPI.api
            .userPhoneDelete(RestAPIParams.DeletePhone(password))
            .submit { finishWithSuccess() }
    }

    private fun updatePhoneNumber(password: String) {
        val phoneToken = mostRecentIntent.getStringExtra(INTENT_EXTRA_PHONE_TOKEN) ?: return
        val source = mostRecentIntent.getStringExtra(INTENT_EXTRA_SOURCE_TYPE) ?: return
        RestAPI.api
            .userPhoneWithToken(RestAPIParams.VerificationPhoneCode(phoneToken, password, source))
            .submit { finishWithSuccess() }
    }

    /**
     * The one request shape all three actions share: dim, run, and route a rejection back into the
     * field rather than into a toast when the API told us which field was wrong.
     */
    private fun <T> Observable<T>.submit(onSuccess: (T) -> Unit) {
        restSubscribeOn()
            .withDimmer(dimmer)
            .ui(this@WidgetUserPasswordVerify)
            .appSubscribe(
                errorClass = WidgetUserPasswordVerify::class.java,
                context = context,
                errorHandler = { error -> error.showErrorToasts = !maybeHandleApiError(error) },
                onNext = onSuccess,
            )
    }

    /**
     * Renders a `400` whose body named the offending fields underneath those fields.
     *
     * Returns `true` only when every named field was claimed by an input, i.e. when the user can
     * see the whole problem on screen — otherwise the caller lets the generic toast through so the
     * failure is not silent.
     */
    private fun maybeHandleApiError(error: RestApiError): Boolean {
        if (error.type != RestApiError.Type.DISCORD_BAD_REQUEST) return false
        return validationManager.setErrors(error.messages).isEmpty()
    }

    private fun finishWithSuccess() {
        AppToast.show(this, R.string.saved_settings)
        activity?.setResult(android.app.Activity.RESULT_OK)
        @Suppress("DEPRECATION")
        activity?.onBackPressed()
    }

    /**
     * What the entered password is spent on.
     *
     * The value crosses an [Intent] as a [java.io.Serializable] and its ordinals are pinned by the
     * DEX (`UpdateAccountInfo(0)`, `RemovePhoneNumber(1)`, `UpdatePhoneNumber(2)`). The names are
     * `UpperCamelCase` in the reference and kept that way because they are part of that wire shape.
     */
    @Suppress("EnumEntryName")
    enum class Action {
        UpdateAccountInfo,
        RemovePhoneNumber,
        UpdatePhoneNumber,
    }

    companion object {
        private const val FIELD_PASSWORD = "password"

        private const val MIN_PASSWORD_LENGTH = 6
        private const val MAX_PASSWORD_LENGTH = 128

        private const val INTENT_EXTRA_ACTION = "INTENT_EXTRA_ACTION"
        private const val INTENT_EXTRA_DISCRIMINATOR = "INTENT_EXTRA_DISCRIMINATOR"
        private const val INTENT_EXTRA_EMAIL = "INTENT_EXTRA_EMAIL"
        private const val INTENT_EXTRA_EMAIL_TOKEN = "INTENT_EXTRA_EMAIL_TOKEN"
        private const val INTENT_EXTRA_PHONE_TOKEN = "INTENT_EXTRA_PHONE_TOKEN"
        private const val INTENT_EXTRA_SOURCE_TYPE = "INTENT_EXTRA_SOURCE_TYPE"
        private const val INTENT_EXTRA_USERNAME = "INTENT_EXTRA_USERNAME"

        private fun launch(context: Context, launcher: ActivityResultLauncher<Intent>, extras: Bundle) {
            AnalyticsTracker.openModal("Account Settings Password Verification", "")
            AppScreenRouter.launchForResult(
                context,
                launcher,
                WidgetUserPasswordVerify::class.java,
                Intent().putExtras(extras),
            )
        }

        @JvmStatic
        fun launchRemovePhoneNumber(context: Context, launcher: ActivityResultLauncher<Intent>) {
            launch(
                context,
                launcher,
                Bundle().apply { putSerializable(INTENT_EXTRA_ACTION, Action.RemovePhoneNumber) },
            )
        }

        /**
         * Confirms a pending change to the account's identity fields. Every field is optional
         * because the caller only sends what it is actually changing — a null leaves that field
         * untouched by `PATCH users/@me`.
         */
        @JvmStatic
        @JvmOverloads
        fun launchUpdateAccountSettings(
            context: Context,
            launcher: ActivityResultLauncher<Intent>,
            email: String? = null,
            username: String? = null,
            discriminator: String? = null,
            emailToken: String? = null,
        ) {
            launch(
                context,
                launcher,
                Bundle().apply {
                    putSerializable(INTENT_EXTRA_ACTION, Action.UpdateAccountInfo)
                    putString(INTENT_EXTRA_EMAIL, email)
                    putString(INTENT_EXTRA_USERNAME, username)
                    putString(INTENT_EXTRA_DISCRIMINATOR, discriminator)
                    putString(INTENT_EXTRA_EMAIL_TOKEN, emailToken)
                },
            )
        }

        /** Spends the short-lived token `WidgetUserPhoneVerify` obtained from the SMS code. */
        @JvmStatic
        fun launchUpdatePhoneNumber(
            context: Context,
            launcher: ActivityResultLauncher<Intent>,
            phoneToken: String,
            source: String,
        ) {
            launch(
                context,
                launcher,
                Bundle().apply {
                    putSerializable(INTENT_EXTRA_ACTION, Action.UpdatePhoneNumber)
                    putString(INTENT_EXTRA_PHONE_TOKEN, phoneToken)
                    putString(INTENT_EXTRA_SOURCE_TYPE, source)
                },
            )
        }

        /** Registers a launcher that runs [callback] only when the change actually went through. */
        @JvmStatic
        fun registerForResult(fragment: AppFragment, callback: () -> Unit): ActivityResultLauncher<Intent> =
            fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
                if (result.resultCode == android.app.Activity.RESULT_OK) callback()
            }
    }
}
