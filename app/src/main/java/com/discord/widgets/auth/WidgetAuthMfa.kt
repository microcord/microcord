package com.discord.widgets.auth

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.discord.R
import com.discord.i18n.setI18nText
import com.discord.views.CodeVerificationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout

private const val AUTHY_URL = "https://play.google.com/store/apps/details?id=com.authy.authy"
private const val GOOGLE_AUTHENTICATOR_URL =
    "https://play.google.com/store/apps/details?id=com.google.android.apps.authenticator2"

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
        view.findViewById<TextView>(R.id.auth_login_forgot_password).setOnClickListener { showBackupCodeDialog() }
        setActionBarOptionsMenu(R.menu.menu_auth_mfa, onSelected = { item ->
            when (item.itemId) {
                R.id.menu_auth_mfa_backup_codes -> showBackupCodeDialog()
                R.id.menu_auth_mfa_info -> showInfoDialog()
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

    private fun showBackupCodeDialog() {
        val content = layoutInflater.inflate(R.layout.widget_auth_mfa_backup_codes, null)
        val dialog = AlertDialog.Builder(requireContext()).setView(content).create()
        content.findViewById<MaterialButton>(R.id.widget_auth_mfa_backup_codes_cancel)
            .setOnClickListener { dialog.dismiss() }
        content.findViewById<MaterialButton>(R.id.widget_auth_mfa_backup_codes_send).setOnClickListener {
            val input = content.findViewById<TextInputLayout>(R.id.widget_auth_mfa_backup_codes_edittext)
            val code = input.editText?.text?.toString().orEmpty()
            if (code.length < 8) input.error = getString(R.string.two_fa_backup_code_enter_wrong)
            else {
                dialog.dismiss()
                verify(code)
            }
        }
        dialog.show()
    }

    private fun showInfoDialog() {
        val content = layoutInflater.inflate(R.layout.widget_auth_mfa_info, null)
        val dialog = AlertDialog.Builder(requireContext()).setView(content).create()
        content.findViewById<TextView>(R.id.widget_auth_mfa_info_text).setI18nText(
            R.string.two_fa_download_app_body,
            "authyURL" to AUTHY_URL,
            "googleAuthURL" to GOOGLE_AUTHENTICATOR_URL,
        )
        content.findViewById<MaterialButton>(R.id.widget_auth_mfa_info_okay).setOnClickListener { dialog.dismiss() }
        dialog.show()
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
