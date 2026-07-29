package com.discord.widgets.auth

import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.i18n.i18nRender
import com.discord.views.CodeVerificationView

class WidgetAuthPhoneVerify : AuthFragment(R.layout.widget_auth_phone_verify, AuthRoute.PHONE_VERIFICATION) {
    private var ignoreAutopaste = true

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val verification = view.findViewById<CodeVerificationView>(R.id.auth_phone_verify_code)
        verification.onCodeEntered = ::verify
        registerForContextMenu(verification)
        view.findViewById<TextView>(R.id.auth_phone_verify_resend).setOnClickListener {
            model.state.phone?.let { submit(AuthAction.ResendPhoneCode(it)) }
        }
        view.findViewById<TextView>(R.id.auth_phone_bailout).apply {
            visibility = if (model.state.email != null) View.VISIBLE else View.GONE
            setOnClickListener {
                actions.navigate(
                    AuthRoute.REGISTER_IDENTITY,
                    model.state.copy(route = AuthRoute.REGISTER_IDENTITY, identityMode = AuthIdentityMode.EMAIL),
                )
            }
        }
    }

    private fun verify(code: String) {
        val phone = model.state.phone.orEmpty()
        if (phone.isBlank()) model.update { it.copy(fieldErrors = mapOf("code" to "Phone number is missing.")) }
        else submit(AuthAction.VerifyPhone(phone, code, model.state.phonePurpose))
    }

    override fun onResume() {
        super.onResume()
        if (ignoreAutopaste) ignoreAutopaste = false else pasteCodeFromClipboard()
    }

    override fun onDestroy() {
        if (model.state.phonePurpose == AuthPhonePurpose.LOGIN && activity?.isChangingConfigurations != true) {
            AuthRequests.cancelPendingLogin()
        }
        super.onDestroy()
    }

    override fun pasteCodeFromClipboard(): Boolean {
        val code = clipboardCode(6, stripSpaces = true) ?: return false
        val verification = view?.findViewById<CodeVerificationView>(R.id.auth_phone_verify_code) ?: return false
        verification.setCode(code)
        verification.postDelayed({ verify(verification.code) }, 500L)
        return true
    }

    override fun render(state: AuthUiState) {
        val title = when (state.phonePurpose) {
            AuthPhonePurpose.REGISTRATION -> R.string.sms_confirmation_title
            AuthPhonePurpose.PASSWORD_RESET -> R.string.password_recovery_verify_phone_title
            AuthPhonePurpose.LOGIN -> R.string.phone_ip_authorization_title
        }
        val subtitle = when (state.phonePurpose) {
            AuthPhonePurpose.REGISTRATION -> requireContext().i18nRender(
                R.string.sms_confirmation_description,
                "phone" to state.phone.orEmpty(),
            )
            AuthPhonePurpose.PASSWORD_RESET -> getText(R.string.password_recovery_verify_phone_subtitle)
            AuthPhonePurpose.LOGIN -> getText(R.string.phone_ip_authorization_subtitle)
        }
        requireView().findViewById<TextView>(R.id.auth_phone_verify_title).setText(title)
        requireView().findViewById<TextView>(R.id.auth_phone_verify_subtitle).text = subtitle
        requireView().findViewById<View>(R.id.dimmer_view).visibility = if (state.loading) View.VISIBLE else View.GONE
        requireView().findViewById<CodeVerificationView>(R.id.auth_phone_verify_code).contentDescription = state.fieldErrors["code"]
    }
}
