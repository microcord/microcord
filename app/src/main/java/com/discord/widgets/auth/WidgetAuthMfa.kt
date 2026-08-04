package com.discord.widgets.auth

import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.api.auth.mfa.MfaMethodType
import com.discord.i18n.i18nRender
import com.discord.views.CodeVerificationView
import com.discord.widgets.mfa.WidgetMfaMethodSheet
import com.google.android.material.button.MaterialButton

class WidgetAuthMfa : AuthFragment(R.layout.widget_auth_mfa, AuthRoute.MFA) {
    private var ignoreAutopaste = true

    private var chosenMethod: MfaMethodType? = null

    /** The redacted number `auth/mfa/sms/send` replied with; the subtitle keeps showing it. */
    private var smsPhone: String? = null

    /** The methods this login offers that a code field can answer. */
    private val offeredMethods: List<MfaMethodType>
        get() = model.state.mfaChallenge?.methods?.map { it.type }?.filter { it in CODE_METHODS }
            ?: listOf(MfaMethodType.TOTP)

    /** An SMS-only account has no TOTP secret, and `auth/mfa/totp` rejects its code. */
    private val codeMethod: MfaMethodType
        get() = chosenMethod
            ?: CODE_METHODS.firstOrNull { it in offeredMethods }
            ?: MfaMethodType.TOTP

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        if (model.state.ticket.isNullOrBlank()) {
            actions.navigate(AuthRoute.LOGIN)
            return
        }
        val verification = view.findViewById<CodeVerificationView>(R.id.auth_mfa_verification)
        verification.onCodeEntered = { verify(it, codeMethod) }
        registerForContextMenu(verification)
        WidgetAuthMfaDialog.registerForBackupCode(this) { verify(it, MfaMethodType.BACKUP) }
        WidgetMfaMethodSheet.registerForMethod(this, ::selectMethod)
        view.findViewById<TextView>(R.id.auth_mfa_choose_method).apply {
            visibility = if (offeredMethods.size > 1) View.VISIBLE else View.GONE
            setOnClickListener { showMethodChooser() }
        }
        view.findViewById<MaterialButton>(R.id.auth_mfa_resend).setOnClickListener { sendSms() }
        when (codeMethod) {
            MfaMethodType.SMS -> sendSms()
            // Nothing but backup codes is on offer, and they are too long for the field behind it.
            MfaMethodType.BACKUP -> WidgetAuthMfaDialog.showBackupCodes(this)
            else -> Unit
        }
    }

    override fun onResume() {
        super.onResume()
        if (ignoreAutopaste) ignoreAutopaste = false else view?.postDelayed({ pasteCodeFromClipboard() }, 250L)
    }

    private fun showMethodChooser() = WidgetMfaMethodSheet.show(this, offeredMethods)

    /** A backup code is eight characters, so it needs the dialog rather than this screen's field. */
    private fun selectMethod(method: MfaMethodType) {
        if (method == MfaMethodType.BACKUP) {
            WidgetAuthMfaDialog.showBackupCodes(this)
            return
        }
        chosenMethod = method
        view?.findViewById<CodeVerificationView>(R.id.auth_mfa_verification)?.clearCode()
        smsPhone = null
        if (method == MfaMethodType.SMS) sendSms() else render(model.state)
    }

    private fun sendSms() = submit(AuthAction.SendMfaSms(model.state.ticket.orEmpty()))

    private fun verify(code: String, authenticatorType: MfaMethodType) {
        val error = AuthUiRules.code(code, minimumFor(authenticatorType))
        if (error.isNotEmpty()) {
            model.update { it.copy(fieldErrors = error.resolve()) }
            return
        }
        submit(
            AuthAction.VerifyMfa(
                ticket = model.state.ticket.orEmpty(),
                code = code,
                authenticatorType = authenticatorType,
                loginInstanceId = model.state.loginInstanceId,
            ),
        )
    }

    override fun pasteCodeFromClipboard(): Boolean {
        val code = clipboardCode(TOTP_CODE_LENGTH) ?: return false
        val verification = view?.findViewById<CodeVerificationView>(R.id.auth_mfa_verification) ?: return false
        verification.setCode(code)
        postVerify(verification, PASTE_VERIFY_DELAY_MS) { verify(verification.code, codeMethod) }
        return true
    }

    override fun onDestroy() {
        if (activity?.isChangingConfigurations != true) model.update(AuthUiState::clearCredentials)
        super.onDestroy()
    }

    override fun render(state: AuthUiState) {
        val view = view ?: return
        val sms = codeMethod == MfaMethodType.SMS
        state.message?.let { smsPhone = it }

        view.findViewById<View>(R.id.dimmer_view).visibility = if (state.loading) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.auth_mfa_title)
            .setText(if (sms) R.string.enter_code else R.string.mfa_v2_totp)
        view.findViewById<MaterialButton>(R.id.auth_mfa_resend).visibility =
            if (sms) View.VISIBLE else View.GONE

        view.findViewById<TextView>(R.id.auth_mfa_subtitle).apply {
            visibility = if (sms) View.VISIBLE else View.GONE
            text = when (val phone = smsPhone) {
                null -> getText(R.string.two_fa_enter_sms_token_sending)
                else -> requireContext().i18nRender(
                    R.string.two_fa_enter_sms_token_sent,
                    "phoneNumber" to phone,
                )
            }
        }

        val failure = state.fieldErrors["code"] ?: state.error
        view.findViewById<TextView>(R.id.auth_mfa_error).apply {
            text = failure
            visibility = if (failure == null) View.GONE else View.VISIBLE
        }
        val verification = view.findViewById<CodeVerificationView>(R.id.auth_mfa_verification)
        verification.contentDescription = failure
        // A rejected code fills every cell, and a full field accepts no more input.
        if (failure != null) verification.clearCode()
    }

    private companion object {
        const val TOTP_CODE_LENGTH = 6
        const val BACKUP_CODE_LENGTH = 8
        const val PASTE_VERIFY_DELAY_MS = 500L

        /** In preference order, and WebAuthn is absent: a code field cannot answer it. */
        val CODE_METHODS = listOf(MfaMethodType.TOTP, MfaMethodType.SMS, MfaMethodType.BACKUP)

        fun minimumFor(authenticatorType: MfaMethodType) =
            if (authenticatorType == MfaMethodType.BACKUP) BACKUP_CODE_LENGTH else TOTP_CODE_LENGTH
    }
}
