package com.discord.widgets.settings.account.mfa

import android.view.View
import com.discord.R
import com.discord.app.AppDialog
import com.discord.restapi.RestAPIParams
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.views.LoadingButton
import com.google.android.material.textfield.TextInputLayout

class WidgetEnableTwoFactorPasswordDialog : AppDialog(R.layout.widget_enable_two_factor_password_dialog) {
    var onValidPasswordEntered: (String) -> Unit = {}

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        isCancelable = false
        val password = view.findViewById<TextInputLayout>(R.id.enable_two_factor_password_view_input)
        val confirm = view.findViewById<LoadingButton>(R.id.enable_two_factor_password_ok)
        confirm.setIsLoading(false)
        confirm.setOnClickListener {
            confirm.setIsLoading(true)
            RestAPI.api.enableMFA(RestAPIParams.EnableMFA("random code", "random secret", password.getTextOrEmpty()))
                .restSubscribeOn()
                .ui(this)
                .appSubscribe(
                    errorClass = WidgetEnableTwoFactorPasswordDialog::class.java,
                    context = view.context,
                    errorHandler = { error ->
                        confirm.setIsLoading(false)
                        if (error.code == PASSWORD_VERIFIED_CODE) {
                            error.showErrorToasts = false
                            dismiss()
                            onValidPasswordEntered(password.getTextOrEmpty())
                        }
                    },
                ) { }
        }
        view.findViewById<View>(R.id.enable_two_factor_password_cancel).setOnClickListener {
            activity?.onBackPressedDispatcher?.onBackPressed()
        }
    }

    companion object {
        private const val PASSWORD_VERIFIED_CODE = 60005
    }
}
