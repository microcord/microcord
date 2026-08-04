package com.discord.widgets.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import com.discord.R
import com.discord.app.AppDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout

/** Takes an eight-character backup code, which the six-cell field on the MFA screen cannot. */
class WidgetAuthMfaDialog : AppDialog() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.widget_auth_mfa_backup_codes, container, false)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
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

    companion object {
        private const val REQUEST_BACKUP_CODE = "widget_auth_mfa_backup_code"
        private const val RESULT_CODE = "code"
        private const val BACKUP_CODE_DIGITS = 8

        fun registerForBackupCode(fragment: Fragment, onCode: (String) -> Unit) {
            fragment.setFragmentResultListener(REQUEST_BACKUP_CODE) { _, result ->
                result.getString(RESULT_CODE)?.let(onCode)
            }
        }

        fun showBackupCodes(fragment: Fragment) = WidgetAuthMfaDialog()
            .show(fragment.parentFragmentManager, WidgetAuthMfaDialog::class.java.name)
    }
}
