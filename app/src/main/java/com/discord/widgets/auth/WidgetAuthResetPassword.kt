package com.discord.widgets.auth

import android.view.View
import com.discord.R
import com.discord.utilities.uri.UriHandler
import com.google.android.material.button.MaterialButton

/** Opens Discord's password-reset web flow without displaying or persisting the reset token. */
class WidgetAuthResetPassword : AuthFragment(R.layout.widget_auth_reset_password, AuthRoute.RESET_PASSWORD) {
    private val token: String
        get() = model.state.resetToken.orEmpty()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        if (token.isEmpty()) {
            actions.close()
            return
        }
        view.findViewById<MaterialButton>(R.id.auth_reset_password_submit).setOnClickListener {
            val resetToken = token
            if (resetToken.isEmpty()) return@setOnClickListener
            UriHandler.handle(requireContext(), AuthRequests.PASSWORD_RESET_URL + resetToken)
            model.update(AuthUiState::clearCredentials)
            actions.close()
        }
        view.findViewById<MaterialButton>(R.id.auth_reset_password_back).setOnClickListener {
            model.update(AuthUiState::clearCredentials)
            actions.close()
        }
    }

    override fun render(state: AuthUiState) {
        requireView().findViewById<MaterialButton>(R.id.auth_reset_password_submit).isEnabled = token.isNotEmpty()
    }

    override fun onDestroy() {
        if (activity?.isChangingConfigurations != true) model.update(AuthUiState::clearCredentials)
        super.onDestroy()
    }
}
