package com.discord.widgets.settings.account

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.AppToast
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.views.LoadingButton
import com.google.android.material.textfield.TextInputLayout

class WidgetEnableSMSBackupDialog : AppDialog(R.layout.widget_enable_sms_backup_dialog) {
    private lateinit var body: TextView
    private lateinit var confirm: LoadingButton
    private lateinit var header: TextView
    private lateinit var password: TextInputLayout

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        body = view.findViewById(R.id.enable_sms_backup_body)
        confirm = view.findViewById(R.id.enable_sms_backup_confirm)
        header = view.findViewById(R.id.enable_sms_backup_header)
        password = view.findViewById(R.id.enable_sms_backup_password_wrap)
        view.findViewById<View>(R.id.enable_sms_backup_cancel).setOnClickListener { dismiss() }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        val enable = arguments?.getBoolean(EXTRA_ENABLE) ?: return dismiss()
        if (enable) {
            header.setText(R.string.mfa_sms_enable)
            body.setText(R.string.mfa_sms_auth_sales_pitch)
            confirm.setText(getString(R.string.enable))
        } else {
            header.setText(R.string.mfa_sms_remove)
            body.setText(R.string.mfa_sms_confirm_remove_body)
            confirm.setText(getString(R.string.disable))
        }
        confirm.setIsLoading(false)
        confirm.setOnClickListener { enableSMSBackup(enable) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        hideKeyboard()
    }

    private fun enableSMSBackup(enable: Boolean) {
        val body = RestAPIParams.ActivateMfaSMS(password.getTextOrEmpty())
        val request = if (enable) RestAPI.api.enableMfaSMS(body) else RestAPI.api.disableMfaSMS(body)
        confirm.setIsLoading(true)
        request.restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetEnableSMSBackupDialog::class.java,
                context = context,
                errorHandler = { error ->
                    confirm.setIsLoading(false)
                    error.showErrorToasts = false
                    AppToast.show(context, error.message ?: context?.let(error::displayString))
                },
            ) {
                StoreStream.getMFA().togglingSMSBackup()
                confirm.setIsLoading(false)
                dismiss()
            }
    }

    companion object {
        private const val EXTRA_ENABLE = "extra_enable"

        @JvmStatic
        fun show(fragmentManager: FragmentManager, enable: Boolean) {
            WidgetEnableSMSBackupDialog().apply {
                arguments = Bundle().apply { putBoolean(EXTRA_ENABLE, enable) }
            }.show(fragmentManager, WidgetEnableSMSBackupDialog::class.java.simpleName)
        }
    }
}
