package com.discord.widgets.auth

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.discord.app.AppFragment
import androidx.fragment.app.viewModels
import com.discord.R
import com.discord.app.AppViewFlipper
import com.discord.utilities.captcha.CaptchaHelper
import com.discord.utilities.uri.UriHandler
import com.discord.views.CodeVerificationView
import com.discord.views.LoadingButton
import com.discord.views.ScreenTitleView
import com.discord.views.phone.PhoneOrEmailInputView
import com.discord.views.segmentedcontrol.CardSegment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import rx.Subscription

abstract class AuthFragment(
    @LayoutRes layoutId: Int,
    private val route: AuthRoute,
) : AppFragment(layoutId) {
    var authUiActions: AuthUiActions? = null
    protected val model: AuthUiViewModel by viewModels()
    private var stateSubscription: Subscription? = null

    protected val actions: AuthUiActions
        get() = authUiActions ?: (activity as? AuthUiActions)
        ?: error("Auth host must implement AuthUiActions or inject authUiActions")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val restored = savedInstanceState?.getSerializable(STATE) as? AuthUiState
            ?: @Suppress("DEPRECATION") (arguments?.getSerializable(STATE) as? AuthUiState)
        model.restore((restored ?: AuthUiState(route = route)).copy(route = route))
    }

    override fun onStart() {
        super.onStart()
        stateSubscription = model.observe().subscribe(::render)
    }

    override fun onStop() {
        stateSubscription?.unsubscribe()
        stateSubscription = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        captureState()
        outState.putSerializable(STATE, model.state)
        super.onSaveInstanceState(outState)
    }

    protected open fun captureState() = Unit
    protected open fun render(state: AuthUiState) = Unit

    /**
     * The action a captcha is currently blocking, replayed once the challenge is solved.
     *
     * Deliberately in memory only: it can hold a password, so it must never reach saved instance
     * state or a log. If the process dies mid-challenge the user re-enters the form, which is the
     * correct trade.
     */
    private var captchaBlockedAction: AuthAction? = null

    private val captchaLauncher = WidgetAuthCaptcha.registerForResult(this) { payload ->
        captchaBlockedAction?.let { blocked ->
            captchaBlockedAction = null
            submit(blocked, payload)
        }
    }

    protected fun submit(action: AuthAction, captcha: CaptchaHelper.CaptchaPayload? = null) {
        model.update { it.copy(loading = true, fieldErrors = emptyMap(), message = null) }
        actions.submit(action, captcha) { result ->
            if (!isAdded) return@submit
            requireActivity().runOnUiThread {
                when (result) {
                    is AuthResult.Success -> {
                        model.update { it.copy(loading = false, message = result.message) }
                        result.nextRoute?.let(actions::navigate)
                    }
                    is AuthResult.Failure -> model.update {
                        it.copy(loading = false, fieldErrors = result.fieldErrors, message = result.message)
                    }
                    is AuthResult.Challenge -> {
                        model.update { it.copy(loading = false) }
                        actions.navigate(result.route, result.state.copy(route = result.route))
                    }
                    // Hold the action, run the challenge, and re-submit it verbatim afterwards so
                    // the user never re-types the form.
                    is AuthResult.CaptchaRequired -> {
                        model.update { it.copy(loading = false) }
                        captchaBlockedAction = action
                        WidgetAuthCaptcha.launch(requireContext(), captchaLauncher, result.error)
                    }
                }
            }
        }
    }

    protected fun TextInputLayout.text() = editText?.text?.toString().orEmpty()
    protected fun TextInputLayout.setErrorFrom(state: AuthUiState, key: String) { error = state.fieldErrors[key] }
    protected fun TextInputLayout.onDone(action: () -> Unit) {
        editText?.setOnEditorActionListener { _, id, event ->
            val done = id == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (done) action()
            done
        }
    }

    protected fun chooseBirthday(current: Long?, callback: (Long) -> Unit) {
        val initial = Calendar.getInstance().apply {
            timeInMillis = current ?: run {
                add(Calendar.YEAR, -18)
                timeInMillis
            }
        }
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                callback(Calendar.getInstance().apply { set(year, month, day, 12, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis)
            },
            initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH),
        ).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
    }

    companion object {
        private const val STATE = "auth_ui_state"
        fun arguments(state: AuthUiState) = Bundle().apply { putSerializable(STATE, state) }
    }
}

class WidgetAuthLanding : AuthFragment(R.layout.widget_auth_landing, AuthRoute.LANDING) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<MaterialButton>(R.id.auth_landing_login).setOnClickListener { actions.navigate(AuthRoute.LOGIN) }
    }

    override fun render(state: AuthUiState) {
        val flipper = view?.findViewById<AppViewFlipper>(R.id.auth_landing_switch_flipper) ?: return
        flipper.displayedChild = if (state.landingContent == null) 0 else 1
        state.landingContent?.let { requireView().findViewById<AuthInviteInfoView>(R.id.auth_landing_invite_info).configure(it) }
        requireView().findViewById<MaterialButton>(R.id.auth_landing_register).setOnClickListener {
            if (state.underageMessage != null) actions.navigate(AuthRoute.AGE_GATED, state.copy(route = AuthRoute.AGE_GATED))
            else actions.navigate(AuthRoute.REGISTER_IDENTITY, state.copy(route = AuthRoute.REGISTER_IDENTITY))
        }
    }
}

class WidgetAuthLogin : AuthFragment(R.layout.widget_auth_login, AuthRoute.LOGIN) {
    private val input get() = requireView().findViewById<PhoneOrEmailInputView>(R.id.auth_login_login_input)
    private val password get() = requireView().findViewById<TextInputLayout>(R.id.auth_login_password_wrap)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        input.bind(this)
        if (input.getTextOrEmpty().isBlank()) input.setText(model.state.identifier)
        view.findViewById<MaterialButton>(R.id.auth_login).setOnClickListener { login() }
        password.onDone(::login)
        view.findViewById<TextView>(R.id.auth_login_forgot_password).setOnClickListener {
            captureState(); submit(AuthAction.ForgotPassword(model.state.identifier))
        }
        view.findViewById<TextView>(R.id.auth_login_password_manager_link).setOnClickListener { actions.openPasswordManager() }
        view.findViewById<TextView>(R.id.auth_login_token_link).setOnClickListener { showTokenLoginDialog() }
    }

    private fun login() {
        captureState()
        val errors = AuthUiRules.login(model.state.identifier, password.text())
        if (errors.isNotEmpty()) model.update { it.copy(fieldErrors = errors) }
        else submit(AuthAction.Login(model.state.identifier, password.text()))
    }

    private fun showTokenLoginDialog() {
        val input = TextInputEditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        val wrapper = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.manticord_token)
            addView(input)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.manticord_login_with_token)
            .setView(wrapper)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.login, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val token = input.text?.toString().orEmpty()
                val errors = AuthUiRules.token(token)
                wrapper.error = errors["token"]
                if (errors.isEmpty()) {
                    input.text?.clear()
                    dialog.dismiss()
                    submit(AuthAction.TokenLogin(token))
                }
            }
        }
        dialog.setOnDismissListener { input.text?.clear() }
        dialog.show()
    }

    override fun captureState() = model.update { it.copy(identifier = input.getTextOrEmpty()) }
    override fun render(state: AuthUiState) {
        input.mainTextInputLayout.error = state.fieldErrors["identifier"]
        password.setErrorFrom(state, "password")
        requireView().findViewById<View>(R.id.dimmer_view).visibility = if (state.loading) View.VISIBLE else View.GONE
    }
}

class WidgetAuthRegisterIdentity : AuthFragment(R.layout.widget_auth_register_identity, AuthRoute.REGISTER_IDENTITY) {
    private val input get() = requireView().findViewById<PhoneOrEmailInputView>(R.id.auth_register_identity_input)
    private val first get() = requireView().findViewById<CardSegment>(R.id.auth_register_identity_first_segment_card)
    private val second get() = requireView().findViewById<CardSegment>(R.id.auth_register_identity_second_segment_card)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        input.bind(this)
        first.setText(getString(R.string.email)); second.setText(getString(R.string.phone))
        first.setOnClickListener { setMode(AuthIdentityMode.EMAIL) }
        second.setOnClickListener { setMode(AuthIdentityMode.PHONE) }
        view.findViewById<MaterialButton>(R.id.auth_register_identity_button).setOnClickListener { next() }
        setMode(model.state.identityMode)
    }

    private fun setMode(mode: AuthIdentityMode) {
        model.update { it.copy(identityMode = mode, fieldErrors = emptyMap()) }
        input.setMode(if (mode == AuthIdentityMode.EMAIL) PhoneOrEmailInputView.Mode.EMAIL else PhoneOrEmailInputView.Mode.PHONE)
        input.setHint(getString(if (mode == AuthIdentityMode.EMAIL) R.string.form_label_email else R.string.form_label_phone_number))
        first.setSegmentSelected(mode == AuthIdentityMode.EMAIL); second.setSegmentSelected(mode == AuthIdentityMode.PHONE)
    }

    private fun next() {
        val value = input.getTextOrEmpty().trim()
        val errors = AuthUiRules.identity(model.state.identityMode, value)
        if (errors.isNotEmpty()) model.update { it.copy(fieldErrors = errors) }
        else if (model.state.identityMode == AuthIdentityMode.EMAIL) {
            actions.navigate(AuthRoute.REGISTER_ACCOUNT, AuthUiState(route = AuthRoute.REGISTER_ACCOUNT, email = value))
        } else submit(AuthAction.SendPhoneCode(value))
    }

    override fun render(state: AuthUiState) {
        input.mainTextInputLayout.error = state.fieldErrors["identifier"]
        requireView().findViewById<View>(R.id.dimmer_view).visibility = if (state.loading) View.VISIBLE else View.GONE
    }
}

class WidgetAuthRegisterAccountInformation : AuthFragment(R.layout.widget_auth_register_account_information, AuthRoute.REGISTER_ACCOUNT) {
    private val username get() = requireView().findViewById<TextInputLayout>(R.id.auth_register_account_information_username_wrap)
    private val password get() = requireView().findViewById<TextInputLayout>(R.id.auth_register_account_information_password_wrap)
    private val consent get() = requireView().findViewById<CheckBox>(R.id.auth_tos_opt_in)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        username.editText?.setText(model.state.username)
        consent.visibility = if (model.state.consentRequired) View.VISIBLE else View.GONE
        view.findViewById<LoadingButton>(R.id.auth_register_account_information_button).setOnClickListener { next() }
        password.onDone(::next)
    }

    private fun next() {
        captureState()
        val state = model.state
        val errors = AuthUiRules.account(state.username, password.text(), state.consentRequired, state.consented)
        if (errors.isNotEmpty()) model.update { it.copy(fieldErrors = errors) }
        else if (state.birthday == null) actions.navigate(AuthRoute.BIRTHDAY, state.copy(route = AuthRoute.BIRTHDAY))
        else submit(AuthAction.Register(state.email, state.phoneToken, state.username, password.text(), state.birthday, state.consented))
    }

    override fun captureState() = model.update { it.copy(username = username.text(), consented = consent.isChecked) }
    override fun render(state: AuthUiState) {
        username.setErrorFrom(state, "username"); password.setErrorFrom(state, "password")
        consent.error = state.fieldErrors["consent"]
        requireView().findViewById<LoadingButton>(R.id.auth_register_account_information_button).setIsLoading(state.loading)
    }
}

class WidgetAuthBirthday : AuthFragment(R.layout.widget_auth_birthday, AuthRoute.BIRTHDAY) {
    private val birthday get() = requireView().findViewById<TextInputLayout>(R.id.auth_register_birthday)
    private val consent get() = requireView().findViewById<CheckBox>(R.id.auth_tos_opt_in)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        birthday.editText?.apply { isFocusable = false; setOnClickListener { chooseBirthday(model.state.birthday, ::setBirthday) } }
        birthday.setOnClickListener { chooseBirthday(model.state.birthday, ::setBirthday) }
        consent.visibility = if (model.state.consentRequired) View.VISIBLE else View.GONE
        view.findViewById<MaterialButton>(R.id.auth_register_button).setOnClickListener { submitBirthday() }
    }

    private fun setBirthday(value: Long) = model.update { it.copy(birthday = value, fieldErrors = emptyMap()) }
    private fun submitBirthday() {
        val state = model.state.copy(consented = consent.isChecked)
        when {
            state.birthday == null -> model.update { it.copy(fieldErrors = mapOf("birthday" to "Birthday is required.")) }
            state.consentRequired && !state.consented -> model.update { it.copy(fieldErrors = mapOf("consent" to "You must agree before continuing.")) }
            else -> actions.navigate(AuthRoute.REGISTER_ACCOUNT, state.copy(route = AuthRoute.REGISTER_ACCOUNT))
        }
    }

    override fun render(state: AuthUiState) {
        birthday.editText?.setText(state.birthday?.let { DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it)) }.orEmpty())
        birthday.setErrorFrom(state, "birthday"); consent.error = state.fieldErrors["consent"]
    }
}

class WidgetAuthMfa : AuthFragment(R.layout.widget_auth_mfa, AuthRoute.MFA) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<CodeVerificationView>(R.id.auth_mfa_verification).onCodeEntered = ::verify
        view.findViewById<TextView>(R.id.auth_login_forgot_password).setOnClickListener { showBackupCodeDialog() }
    }

    private fun verify(code: String) {
        val error = AuthUiRules.code(code)
        if (error.isNotEmpty()) model.update { it.copy(fieldErrors = error) }
        else submit(AuthAction.VerifyMfa(model.state.ticket.orEmpty(), code))
    }

    private fun showBackupCodeDialog() {
        val content = layoutInflater.inflate(R.layout.widget_auth_mfa_backup_codes, null)
        val dialog = AlertDialog.Builder(requireContext()).setView(content).create()
        content.findViewById<MaterialButton>(R.id.widget_auth_mfa_backup_codes_cancel).setOnClickListener { dialog.dismiss() }
        content.findViewById<MaterialButton>(R.id.widget_auth_mfa_backup_codes_send).setOnClickListener {
            val code = content.findViewById<TextInputLayout>(R.id.widget_auth_mfa_backup_codes_edittext).text()
            if (code.length < 8) content.findViewById<TextInputLayout>(R.id.widget_auth_mfa_backup_codes_edittext).error = "Enter a valid backup code."
            else { dialog.dismiss(); verify(code) }
        }
        dialog.show()
    }

    override fun render(state: AuthUiState) {
        requireView().findViewById<View>(R.id.dimmer_view).visibility = if (state.loading) View.VISIBLE else View.GONE
        requireView().findViewById<CodeVerificationView>(R.id.auth_mfa_verification).contentDescription = state.fieldErrors["code"]
    }
}

class WidgetAuthPhoneVerify : AuthFragment(R.layout.widget_auth_phone_verify, AuthRoute.PHONE_VERIFICATION) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<CodeVerificationView>(R.id.auth_phone_verify_code).onCodeEntered = ::verify
        view.findViewById<TextView>(R.id.auth_phone_verify_resend).setOnClickListener { model.state.phone?.let { submit(AuthAction.ResendPhoneCode(it)) } }
        view.findViewById<TextView>(R.id.auth_phone_bailout).apply {
            visibility = if (model.state.email != null) View.VISIBLE else View.GONE
            setOnClickListener { actions.navigate(AuthRoute.REGISTER_IDENTITY, model.state.copy(route = AuthRoute.REGISTER_IDENTITY, identityMode = AuthIdentityMode.EMAIL)) }
        }
    }

    private fun verify(code: String) {
        val phone = model.state.phone.orEmpty()
        if (phone.isBlank()) model.update { it.copy(fieldErrors = mapOf("code" to "Phone number is missing.")) }
        else submit(AuthAction.VerifyPhone(phone, code))
    }

    override fun render(state: AuthUiState) {
        state.message?.let { requireView().findViewById<TextView>(R.id.auth_phone_verify_subtitle).text = it }
        requireView().findViewById<View>(R.id.dimmer_view).visibility = if (state.loading) View.VISIBLE else View.GONE
        requireView().findViewById<CodeVerificationView>(R.id.auth_phone_verify_code).contentDescription = state.fieldErrors["code"]
    }
}

class WidgetAuthEmailVerify : AuthFragment(R.layout.widget_user_email_verify, AuthRoute.EMAIL_VERIFICATION) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<MaterialButton>(R.id.alert_verify_email_resend).setOnClickListener { submit(AuthAction.ResendEmail(model.state.email)) }
        view.findViewById<MaterialButton>(R.id.alert_verify_email_change).setOnClickListener {
            actions.navigate(AuthRoute.REGISTER_IDENTITY, model.state.copy(route = AuthRoute.REGISTER_IDENTITY, identityMode = AuthIdentityMode.EMAIL))
        }
    }

    override fun render(state: AuthUiState) {
        requireView().findViewById<MaterialButton>(R.id.alert_verify_email_resend).isEnabled = !state.loading
    }
}

class WidgetAuthRequiredAction : AuthFragment(R.layout.widget_user_account_verify, AuthRoute.REQUIRED_ACTION) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.verify_account_by_email).setOnClickListener { navigate(AuthRequiredAction.VERIFY_EMAIL) }
        view.findViewById<View>(R.id.verify_account_by_phone).setOnClickListener { navigate(AuthRequiredAction.VERIFY_PHONE) }
        view.findViewById<TextView>(R.id.verify_account_support).setOnClickListener { actions.openSupport() }
    }

    private fun navigate(action: AuthRequiredAction) = actions.navigate(AuthUiRules.routeFor(action), model.state.copy(route = AuthUiRules.routeFor(action)))
    override fun render(state: AuthUiState) {
        requireView().findViewById<View>(R.id.verify_account_by_email).visibility = if (state.allowEmail) View.VISIBLE else View.GONE
        requireView().findViewById<View>(R.id.verify_account_by_phone).visibility = if (state.allowPhone) View.VISIBLE else View.GONE
        state.requiredAction?.takeIf { it == AuthRequiredAction.UPDATE_PASSWORD }?.let { navigate(it) }
    }
}

class WidgetAgeVerify : AuthFragment(R.layout.widget_age_verify, AuthRoute.AGE_VERIFY) {
    private val flipper get() = requireView().findViewById<AppViewFlipper>(R.id.age_verify_view_flipper)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextInputLayout>(R.id.age_verify_input_wrapper).apply {
            editText?.isFocusable = false
            editText?.setOnClickListener { chooseBirthday(model.state.birthday, ::setBirthday) }
        }
        view.findViewById<LoadingButton>(R.id.age_verify_next_button).setOnClickListener { next() }
        view.findViewById<LoadingButton>(R.id.age_verify_confirm_button).setOnClickListener { model.state.birthday?.let { submit(AuthAction.VerifyAge(it)) } }
        view.findViewById<MaterialButton>(R.id.age_verify_confirm_back_button).setOnClickListener { model.update { it.copy(ageStep = AuthAgeStep.VERIFY) } }
        view.findViewById<MaterialButton>(R.id.age_verify_return_to_login_button).setOnClickListener { actions.close() }
    }

    private fun setBirthday(value: Long) = model.update { it.copy(birthday = value, fieldErrors = emptyMap()) }
    private fun next() {
        if (model.state.birthday == null) model.update { it.copy(fieldErrors = mapOf("birthday" to "Birthday is required.")) }
        else model.update { it.copy(ageStep = AuthAgeStep.CONFIRM) }
    }

    override fun render(state: AuthUiState) {
        flipper.displayedChild = state.ageStep.ordinal
        val input = requireView().findViewById<TextInputLayout>(R.id.age_verify_input_wrapper)
        input.editText?.setText(state.birthday?.let { DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it)) }.orEmpty())
        input.setErrorFrom(state, "birthday")
        requireView().findViewById<LoadingButton>(R.id.age_verify_next_button).apply { isEnabled = state.birthday != null; setIsLoading(state.loading) }
        requireView().findViewById<LoadingButton>(R.id.age_verify_confirm_button).setIsLoading(state.loading)
        state.birthday?.let { birthday ->
            val now = Calendar.getInstance()
            val born = Calendar.getInstance().apply { timeInMillis = birthday }
            var age = now.get(Calendar.YEAR) - born.get(Calendar.YEAR)
            if (now.get(Calendar.DAY_OF_YEAR) < born.get(Calendar.DAY_OF_YEAR)) age--
            requireView().findViewById<TextView>(R.id.age_verify_confirm_title).text = getString(R.string.age_gate_confirm_header).replace("{age}", age.toString())
        }
        state.underageMessage?.let { requireView().findViewById<TextView>(R.id.age_verify_underage_description).text = it }
    }
}

class WidgetAuthAgeGated : AuthFragment(R.layout.widget_auth_age_gated, AuthRoute.AGE_GATED) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<MaterialButton>(R.id.auth_register_underage_button).setOnClickListener { actions.navigate(AuthRoute.LOGIN) }
    }

    override fun render(state: AuthUiState) {
        state.underageMessage?.let { requireView().findViewById<TextView>(R.id.auth_register_underage_description).text = it }
    }
}

class WidgetAuthForceChangePassword : AuthFragment(R.layout.widget_settings_account_change_password, AuthRoute.FORCE_PASSWORD) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<ImageView>(R.id.auth_change_password_icon).visibility = View.VISIBLE
        view.findViewById<LoadingButton>(R.id.change_password_save).apply {
            setText(getString(R.string.update_password)); setOnClickListener { changePassword() }
        }
    }

    private fun changePassword() {
        val current = requireView().findViewById<TextInputLayout>(R.id.change_password_current_password_input)
        val next = requireView().findViewById<TextInputLayout>(R.id.change_password_new_password_input)
        if (current.text().isBlank()) current.error = "Current password is required."
        else if (next.text().length < 6) next.error = "Password must be at least 6 characters."
        else submit(AuthAction.ChangePassword(current.text(), next.text(), requireView().findViewById<TextInputLayout>(R.id.change_password_two_factor).text().ifBlank { null }))
    }

    override fun render(state: AuthUiState) = requireView().findViewById<LoadingButton>(R.id.change_password_save).setIsLoading(state.loading)
}

/**
 * Landing screen for a `discord.com/reset?token=…` link.
 *
 * The app never takes the new password itself: the reference hands the reset token straight to the
 * web flow, which is also where the password-strength rules and the session invalidation live.
 * Both buttons therefore finish the screen — one after opening the web page, one without.
 *
 * The token is a single-use credential. It is read from the launch intent, kept only in memory for
 * the lifetime of this screen, appended to the reset URL, and never logged or persisted.
 */
class WidgetAuthResetPassword : AuthFragment(R.layout.widget_auth_reset_password, AuthRoute.RESET_PASSWORD) {

    private val token: String
        get() = model.state.resetToken
            ?: arguments?.getString(EXTRA_TOKEN)
            ?: mostRecentIntent.getStringExtra(EXTRA_TOKEN)
            ?: ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<MaterialButton>(R.id.auth_reset_password_submit).setOnClickListener {
            val resetToken = token
            if (resetToken.isEmpty()) return@setOnClickListener
            UriHandler.handle(requireContext(), AuthRequests.PASSWORD_RESET_URL + resetToken)
            actions.close()
        }
        view.findViewById<MaterialButton>(R.id.auth_reset_password_back).setOnClickListener { actions.close() }
    }

    override fun render(state: AuthUiState) {
        // Nothing on this screen is token-derived: showing the token would put a live credential
        // on screen (and into screenshots). The message slot is only used for the expiry notice.
        val submit = requireView().findViewById<MaterialButton>(R.id.auth_reset_password_submit)
        submit.isEnabled = token.isNotEmpty()
    }

    companion object {
        const val EXTRA_TOKEN = "INTENT_EXTRA_TOKEN"

        /** Builds the arguments for a reset link opened from outside the app. */
        fun arguments(token: String) = Bundle().apply { putString(EXTRA_TOKEN, token) }
    }
}

class WidgetAuthUndeleteAccount : AuthFragment(R.layout.widget_auth_undelete_account, AuthRoute.UNDELETE_ACCOUNT) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<MaterialButton>(R.id.auth_undelete_delete).setOnClickListener { submit(AuthAction.UndeleteAccount(false)) }
        view.findViewById<MaterialButton>(R.id.auth_undelete_cancel).setOnClickListener { submit(AuthAction.UndeleteAccount(true)) }
    }

    override fun render(state: AuthUiState) {
        if (state.disabledAccount) requireView().findViewById<ScreenTitleView>(R.id.auth_undelete_title).apply {
            setTitle(getString(R.string.account_disabled_title)); setSubtitle(getString(R.string.account_disabled_description))
        }
    }
}
