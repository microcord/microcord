package com.discord.widgets.auth

import android.view.View
import android.widget.ImageView
import com.discord.R
import com.discord.stores.StoreStream
import com.discord.views.LoadingButton
import com.google.android.material.textfield.TextInputLayout

class WidgetAuthForceChangePassword :
    AuthFragment(R.layout.widget_settings_account_change_password, AuthRoute.FORCE_PASSWORD) {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<ImageView>(R.id.auth_change_password_icon).visibility = View.VISIBLE
        view.findViewById<LoadingButton>(R.id.change_password_save).apply {
            setText(getString(R.string.update_password))
            setOnClickListener { changePassword() }
        }
        setOnBackPressed(handler = {
            StoreStream.getAuthentication().logout()
            true
        })
    }

    private fun changePassword() {
        val current = requireView().findViewById<TextInputLayout>(R.id.change_password_current_password_input)
        val next = requireView().findViewById<TextInputLayout>(R.id.change_password_new_password_input)
        val currentPassword = current.editText?.text?.toString().orEmpty()
        val newPassword = next.editText?.text?.toString().orEmpty()
        when {
            currentPassword.isBlank() -> current.error = "Current password is required."
            newPassword.length < 6 -> next.error = "Password must be at least 6 characters."
            else -> submit(
                AuthAction.ChangePassword(
                    currentPassword,
                    newPassword,
                    requireView().findViewById<TextInputLayout>(R.id.change_password_two_factor)
                        .editText?.text?.toString()?.ifBlank { null },
                ),
            )
        }
    }

    override fun render(state: AuthUiState) =
        requireView().findViewById<LoadingButton>(R.id.change_password_save).setIsLoading(state.loading)
}
