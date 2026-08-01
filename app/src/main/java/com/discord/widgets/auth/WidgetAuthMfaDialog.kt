package com.discord.widgets.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import com.discord.R
import com.discord.app.AppDialog
import com.discord.i18n.setI18nText
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout

class WidgetAuthMfaDialog : AppDialog() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(
        if (argumentsOrDefault.getBoolean(ARG_INFO)) {
            R.layout.widget_auth_mfa_info
        } else {
            R.layout.widget_auth_mfa_backup_codes
        },
        container,
        false,
    )

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        if (argumentsOrDefault.getBoolean(ARG_INFO)) bindInfo(view) else bindBackupCodes(view)
    }

    private fun bindBackupCodes(view: View) {
        val input = view.findViewById<TextInputLayout>(R.id.widget_auth_mfa_backup_codes_edittext)
        input.editText?.isSaveEnabled = false
        view.findViewById<MaterialButton>(R.id.widget_auth_mfa_backup_codes_cancel)
            .setOnClickListener { dismiss() }
        view.findViewById<MaterialButton>(R.id.widget_auth_mfa_backup_codes_send).setOnClickListener {
            val code = input.editText?.text?.toString().orEmpty()
            if (code.length < BACKUP_CODE_DIGITS) {
                Toast.makeText(context, R.string.two_fa_backup_code_enter_wrong, Toast.LENGTH_SHORT).show()
            } else {
                dismiss()
                setFragmentResult(REQUEST_BACKUP_CODE, Bundle().apply { putString(RESULT_CODE, code) })
            }
        }
    }

    private fun bindInfo(view: View) {
        view.findViewById<TextView>(R.id.widget_auth_mfa_info_text).setI18nText(
            R.string.two_fa_download_app_body,
            "authyURL" to AUTHY_URL,
            "googleAuthURL" to GOOGLE_AUTHENTICATOR_URL,
        )
        view.findViewById<MaterialButton>(R.id.widget_auth_mfa_info_okay)
            .setOnClickListener { dismiss() }
    }

    companion object {
        private const val ARG_INFO = "is_info"
        private const val REQUEST_BACKUP_CODE = "widget_auth_mfa_backup_code"
        private const val RESULT_CODE = "code"
        private const val BACKUP_CODE_DIGITS = 8
        private const val AUTHY_URL = "https://play.google.com/store/apps/details?id=com.authy.authy"
        private const val GOOGLE_AUTHENTICATOR_URL =
            "https://play.google.com/store/apps/details?id=com.google.android.apps.authenticator2"

        fun registerForBackupCode(fragment: Fragment, onCode: (String) -> Unit) {
            fragment.setFragmentResultListener(REQUEST_BACKUP_CODE) { _, result ->
                result.getString(RESULT_CODE)?.let(onCode)
            }
        }

        fun showBackupCodes(fragment: Fragment) = show(fragment, false)

        fun showInfo(fragment: Fragment) = show(fragment, true)

        private fun show(fragment: Fragment, info: Boolean) {
            WidgetAuthMfaDialog().apply {
                arguments = Bundle().apply { putBoolean(ARG_INFO, info) }
            }.show(
                fragment.parentFragmentManager,
                "${WidgetAuthMfaDialog::class.java.name}:$info",
            )
        }
    }
}
