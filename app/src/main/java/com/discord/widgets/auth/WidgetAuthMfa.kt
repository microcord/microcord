package com.discord.widgets.auth

import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.views.CodeVerificationView

class WidgetAuthMfa : AuthFragment(R.layout.widget_auth_mfa, AuthRoute.MFA) {
    private var ignoreAutopaste = true

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        if (model.state.ticket.isNullOrBlank()) {
            actions.navigate(AuthRoute.LOGIN)
            return
        }
        val verification = view.findViewById<CodeVerificationView>(R.id.auth_mfa_verification)
        verification.onCodeEntered = ::verify
        registerForContextMenu(verification)
        WidgetAuthMfaDialog.registerForBackupCode(this, ::verify)
        view.findViewById<TextView>(R.id.auth_login_forgot_password).setOnClickListener {
            WidgetAuthMfaDialog.showBackupCodes(this)
        }
        setActionBarOptionsMenu(R.menu.menu_auth_mfa, onSelected = { item ->
            when (item.itemId) {
                R.id.menu_auth_mfa_backup_codes -> WidgetAuthMfaDialog.showBackupCodes(this)
                R.id.menu_auth_mfa_info -> WidgetAuthMfaDialog.showInfo(this)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (ignoreAutopaste) ignoreAutopaste = false else view?.postDelayed({ pasteCodeFromClipboard() }, 250L)
    }

    private fun verify(code: String) {
        val error = AuthUiRules.code(code)
        if (error.isNotEmpty()) model.update { it.copy(fieldErrors = error) }
        else submit(AuthAction.VerifyMfa(model.state.ticket.orEmpty(), code))
    }

    override fun pasteCodeFromClipboard(): Boolean {
        val code = clipboardCode(6) ?: return false
        val verification = view?.findViewById<CodeVerificationView>(R.id.auth_mfa_verification) ?: return false
        verification.setCode(code)
        verification.postDelayed({ verify(verification.code) }, 500L)
        return true
    }

    override fun onDestroy() {
        if (activity?.isChangingConfigurations != true) model.update(AuthUiState::clearCredentials)
        super.onDestroy()
    }

    override fun render(state: AuthUiState) {
        requireView().findViewById<View>(R.id.dimmer_view).visibility = if (state.loading) View.VISIBLE else View.GONE
        requireView().findViewById<CodeVerificationView>(R.id.auth_mfa_verification).contentDescription =
            state.fieldErrors["code"]
    }
}
