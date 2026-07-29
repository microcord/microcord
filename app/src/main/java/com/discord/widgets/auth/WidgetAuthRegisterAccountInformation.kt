package com.discord.widgets.auth

import android.view.View
import android.widget.CheckBox
import com.discord.R
import com.discord.views.LoadingButton
import com.google.android.material.textfield.TextInputLayout

private const val BIRTHDAY_RESULT = "auth_birthday_result"
private const val BIRTHDAY_VALUE = "auth_birthday_value"

class WidgetAuthRegisterAccountInformation :
    AuthFragment(R.layout.widget_auth_register_account_information, AuthRoute.REGISTER_ACCOUNT) {

    private val username
        get() = requireView().findViewById<TextInputLayout>(R.id.auth_register_account_information_username_wrap)
    private val password
        get() = requireView().findViewById<TextInputLayout>(R.id.auth_register_account_information_password_wrap)
    private val consent get() = requireView().findViewById<CheckBox>(R.id.auth_tos_opt_in)
    private var passwordForBirthday: String? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        if (model.state.email == null && model.state.phone != null && model.state.phoneToken == null) {
            actions.navigate(
                AuthRoute.REGISTER_IDENTITY,
                AuthUiState(route = AuthRoute.REGISTER_IDENTITY, identityMode = AuthIdentityMode.PHONE),
            )
            return
        }
        parentFragmentManager.setFragmentResultListener(BIRTHDAY_RESULT, viewLifecycleOwner) { _, result ->
            val password = passwordForBirthday ?: return@setFragmentResultListener
            passwordForBirthday = null
            model.update { it.copy(birthday = result.getLong(BIRTHDAY_VALUE)) }
            val state = model.state
            submit(AuthAction.Register(state.email, state.phoneToken, state.username, password, state.birthday, state.consented))
        }
        username.editText?.setText(model.state.username)
        consent.visibility = if (model.state.consentRequired) View.VISIBLE else View.GONE
        view.findViewById<LoadingButton>(R.id.auth_register_account_information_button).setOnClickListener { next() }
        password.onDone(::next)
    }

    private fun next() {
        captureState()
        val state = model.state
        val passwordText = password.text()
        val errors = AuthUiRules.account(state.username, passwordText, state.consentRequired, state.consented)
        when {
            errors.isNotEmpty() -> model.update { it.copy(fieldErrors = errors) }
            state.birthday == null -> {
                passwordForBirthday = passwordText
                actions.navigate(AuthRoute.BIRTHDAY, state.copy(route = AuthRoute.BIRTHDAY))
            }
            else -> submit(
                AuthAction.Register(state.email, state.phoneToken, state.username, passwordText, state.birthday, state.consented),
            )
        }
    }

    override fun captureState() = model.update {
        it.copy(username = username.text(), consented = consent.isChecked)
    }

    override fun onDestroy() {
        if (activity?.isChangingConfigurations != true) model.update(AuthUiState::clearCredentials)
        super.onDestroy()
    }

    override fun render(state: AuthUiState) {
        username.setErrorFrom(state, "username")
        password.setErrorFrom(state, "password")
        consent.error = state.fieldErrors["consent"]
        requireView().findViewById<LoadingButton>(R.id.auth_register_account_information_button)
            .setIsLoading(state.loading)
    }
}
