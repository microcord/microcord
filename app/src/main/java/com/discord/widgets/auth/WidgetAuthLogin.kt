package com.discord.widgets.auth

import android.text.InputType
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.discord.R
import com.discord.i18n.i18nRender
import com.discord.views.phone.PhoneOrEmailInputView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class WidgetAuthLogin : AuthFragment(R.layout.widget_auth_login, AuthRoute.LOGIN) {
    private val input get() = requireView().findViewById<PhoneOrEmailInputView>(R.id.auth_login_login_input)
    private val password get() = requireView().findViewById<TextInputLayout>(R.id.auth_login_password_wrap)
    private var shownRecoveryMessage: String? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        input.bind(this)
        if (input.getTextOrEmpty().isBlank()) input.setText(model.state.identifier)
        view.findViewById<MaterialButton>(R.id.auth_login).setOnClickListener { login() }
        password.onDone(::login)
        view.findViewById<TextView>(R.id.auth_login_forgot_password).setOnClickListener {
            captureState()
            submit(AuthAction.ForgotPassword(model.state.identifier))
        }
        view.findViewById<TextView>(R.id.auth_login_password_manager_link)
            .setOnClickListener { actions.openPasswordManager() }
        view.findViewById<TextView>(R.id.auth_login_token_link).setOnClickListener { showTokenLoginDialog() }
    }

    private fun login() {
        captureState()
        val errors = AuthUiRules.login(model.state.identifier, password.text())
        if (errors.isNotEmpty()) model.update { it.copy(fieldErrors = errors) }
        else submit(AuthAction.Login(model.state.identifier, password.text()))
    }

    private fun showTokenLoginDialog() {
        val tokenInput = TextInputEditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        val wrapper = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.manticord_token)
            addView(tokenInput)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.manticord_login_with_token)
            .setView(wrapper)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.login, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val token = tokenInput.text?.toString().orEmpty()
                wrapper.error = AuthUiRules.token(token)["token"]
                if (wrapper.error == null) {
                    tokenInput.text?.clear()
                    dialog.dismiss()
                    submit(AuthAction.TokenLogin(token))
                }
            }
        }
        dialog.setOnDismissListener { tokenInput.text?.clear() }
        dialog.show()
    }

    override fun captureState() = model.update { it.copy(identifier = input.getTextOrEmpty()) }

    override fun render(state: AuthUiState) {
        input.mainTextInputLayout.error = state.fieldErrors["identifier"]
        password.setErrorFrom(state, "password")
        requireView().findViewById<View>(R.id.dimmer_view).visibility = if (state.loading) View.VISIBLE else View.GONE
        state.message?.takeIf { it != shownRecoveryMessage }?.let { login ->
            shownRecoveryMessage = login
            Toast.makeText(
                requireContext(),
                requireContext().i18nRender(R.string.email_verification_instructions_body, "email" to login),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
