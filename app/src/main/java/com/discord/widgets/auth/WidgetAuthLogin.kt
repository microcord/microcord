package com.discord.widgets.auth

import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.discord.R
import com.discord.app.AppActivity
import com.discord.i18n.i18nRender
import com.discord.views.phone.PhoneOrEmailInputView
import com.discord.widgets.notice.WidgetNoticeDialog
import com.google.android.material.button.MaterialButton
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
        if (errors.isNotEmpty()) model.update { it.copy(fieldErrors = errors.resolve()) }
        else submit(AuthAction.Login(model.state.identifier, password.text()))
    }

    private fun showTokenLoginDialog() {
        val activity = appActivity as? AppActivity ?: return
        WidgetNoticeDialog.showInputModal(
            activity = activity,
            headerText = getString(R.string.manticord_login_with_token),
            bodyText = "",
            hintText = getString(R.string.manticord_token),
            onOKClicked = { _, token -> submit(AuthAction.TokenLogin(token)) },
            cancelable = true,
            isPassword = true,
            okText = getString(R.string.login),
            shouldHideKeyboard = true,
            inputValidator = { AuthUiRules.token(it)["token"]?.let(::getString) },
        )
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
