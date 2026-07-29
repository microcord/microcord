package com.discord.widgets.auth

import android.view.View
import com.discord.R
import com.discord.views.phone.PhoneOrEmailInputView
import com.discord.views.segmentedcontrol.CardSegment
import com.google.android.material.button.MaterialButton

class WidgetAuthRegisterIdentity : AuthFragment(R.layout.widget_auth_register_identity, AuthRoute.REGISTER_IDENTITY) {
    private val input get() = requireView().findViewById<PhoneOrEmailInputView>(R.id.auth_register_identity_input)
    private val first get() = requireView().findViewById<CardSegment>(R.id.auth_register_identity_first_segment_card)
    private val second get() = requireView().findViewById<CardSegment>(R.id.auth_register_identity_second_segment_card)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        input.bind(this)
        first.setText(getString(R.string.email))
        second.setText(getString(R.string.phone))
        first.setOnClickListener { setMode(AuthIdentityMode.EMAIL) }
        second.setOnClickListener { setMode(AuthIdentityMode.PHONE) }
        view.findViewById<MaterialButton>(R.id.auth_register_identity_button).setOnClickListener { next() }
        setMode(model.state.identityMode)
    }

    private fun setMode(mode: AuthIdentityMode) {
        model.update { it.copy(identityMode = mode, fieldErrors = emptyMap()) }
        input.setMode(if (mode == AuthIdentityMode.EMAIL) PhoneOrEmailInputView.Mode.EMAIL else PhoneOrEmailInputView.Mode.PHONE)
        input.setHint(
            getString(if (mode == AuthIdentityMode.EMAIL) R.string.form_label_email else R.string.form_label_phone_number),
        )
        first.setSegmentSelected(mode == AuthIdentityMode.EMAIL)
        second.setSegmentSelected(mode == AuthIdentityMode.PHONE)
    }

    private fun next() {
        val value = input.getTextOrEmpty().trim()
        val errors = AuthUiRules.identity(model.state.identityMode, value)
        when {
            errors.isNotEmpty() -> model.update { it.copy(fieldErrors = errors) }
            model.state.identityMode == AuthIdentityMode.EMAIL -> actions.navigate(
                AuthRoute.REGISTER_ACCOUNT,
                AuthUiState(route = AuthRoute.REGISTER_ACCOUNT, email = value),
            )
            else -> submit(AuthAction.SendPhoneCode(value))
        }
    }

    override fun render(state: AuthUiState) {
        input.mainTextInputLayout.error = state.fieldErrors["identifier"]
        requireView().findViewById<View>(R.id.dimmer_view).visibility = if (state.loading) View.VISIBLE else View.GONE
    }
}
