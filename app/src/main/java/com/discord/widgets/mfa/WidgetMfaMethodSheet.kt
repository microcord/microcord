package com.discord.widgets.mfa

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import com.discord.R
import com.discord.api.auth.mfa.MfaMethodType
import com.discord.app.AppBottomSheet

/** "Choose how to verify" — the login screen's counterpart to the reference's select step. */
class WidgetMfaMethodSheet : AppBottomSheet() {

    override fun getContentViewResId(): Int = R.layout.widget_mfa_method_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<LinearLayout>(R.id.mfa_method_sheet_list)
        argumentsOrDefault.getStringArrayList(ARG_METHODS).orEmpty().forEach { wireName ->
            val method = MfaMethodType.from(wireName) ?: return@forEach
            list.addMethodRow(method) {
                dismiss()
                setFragmentResult(REQUEST_METHOD, Bundle().apply { putString(RESULT_METHOD, wireName) })
            }
        }
    }

    companion object {
        private const val ARG_METHODS = "methods"
        private const val REQUEST_METHOD = "widget_mfa_method"
        private const val RESULT_METHOD = "method"

        fun registerForMethod(fragment: Fragment, onMethod: (MfaMethodType) -> Unit) {
            fragment.setFragmentResultListener(REQUEST_METHOD) { _, result ->
                result.getString(RESULT_METHOD)?.let(MfaMethodType::from)?.let(onMethod)
            }
        }

        fun show(fragment: Fragment, methods: List<MfaMethodType>) = WidgetMfaMethodSheet().apply {
            arguments = Bundle().apply {
                putStringArrayList(ARG_METHODS, ArrayList(methods.map { it.wireName }))
            }
        }.show(fragment.parentFragmentManager, WidgetMfaMethodSheet::class.java.name)

        /** Appends the shared method row, so both MFA prompts offer the same-looking list. */
        fun LinearLayout.addMethodRow(method: MfaMethodType, onClick: () -> Unit) {
            val row = LayoutInflater.from(context).inflate(R.layout.widget_mfa_method_item, this, false)
            row.findViewById<TextView>(R.id.mfa_method_item_title).setText(method.labelResId())
            row.setOnClickListener { onClick() }
            addView(row)
        }

        fun MfaMethodType.labelResId(): Int = when (this) {
            MfaMethodType.TOTP -> R.string.mfa_v2_totp_name
            MfaMethodType.BACKUP -> R.string.mfa_v2_backup_name
            MfaMethodType.SMS -> R.string.mfa_v2_sms_name
            MfaMethodType.PASSWORD -> R.string.mfa_v2_password_name
            MfaMethodType.WEBAUTHN -> R.string.two_fa_passkey_unsupported_title
        }
    }
}
