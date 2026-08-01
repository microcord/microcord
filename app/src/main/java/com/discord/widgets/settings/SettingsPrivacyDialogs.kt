package com.discord.widgets.settings

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.google.android.material.button.MaterialButton

internal class SettingsPrivacyDefaultsDialog : AppDialog(R.layout.widget_settings_privacy_defaults) {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<MaterialButton>(R.id.settings_privacy_defaults_existing_no)
            .setOnClickListener { finish(false) }
        view.findViewById<MaterialButton>(R.id.settings_privacy_defaults_existing_yes)
            .setOnClickListener { finish(true) }
    }

    private fun finish(applyToExistingGuilds: Boolean) {
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            Bundle().apply {
                putBoolean(RESULT_RESTRICTED, argumentsOrDefault.getBoolean(ARG_RESTRICTED))
                putBoolean(RESULT_APPLY_EXISTING, applyToExistingGuilds)
            },
        )
        dismiss()
    }

    companion object {
        const val REQUEST_KEY = "SETTINGS_PRIVACY_DEFAULTS_RESULT"
        const val RESULT_RESTRICTED = "RESULT_RESTRICTED"
        const val RESULT_APPLY_EXISTING = "RESULT_APPLY_EXISTING"
        private const val ARG_RESTRICTED = "ARG_RESTRICTED"

        fun show(fragmentManager: FragmentManager, restricted: Boolean) {
            SettingsPrivacyDefaultsDialog().apply {
                arguments = Bundle().apply { putBoolean(ARG_RESTRICTED, restricted) }
            }.show(fragmentManager, SettingsPrivacyDefaultsDialog::class.java.name)
        }
    }
}

internal class SettingsPrivacyConsentDialog : AppDialog(R.layout.view_dialog_confirmation) {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        isCancelable = false
        val arguments = argumentsOrDefault
        view.findViewById<TextView>(R.id.view_dialog_confirmation_header)
            .setText(arguments.getInt(ARG_TITLE))
        view.findViewById<TextView>(R.id.view_dialog_confirmation_text)
            .setText(arguments.getInt(ARG_BODY))
        view.findViewById<MaterialButton>(R.id.view_dialog_confirmation_cancel).apply {
            setText(arguments.getInt(ARG_CANCEL))
            setOnClickListener { finish(false) }
        }
        view.findViewById<MaterialButton>(R.id.view_dialog_confirmation_confirm).apply {
            setText(arguments.getInt(ARG_CONFIRM))
            setOnClickListener { finish(true) }
        }
    }

    private fun finish(confirmed: Boolean) {
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            Bundle().apply {
                putString(RESULT_CONSENT_TYPE, argumentsOrDefault.getString(ARG_CONSENT_TYPE))
                putBoolean(RESULT_CONFIRMED, confirmed)
            },
        )
        dismiss()
    }

    companion object {
        const val REQUEST_KEY = "SETTINGS_PRIVACY_CONSENT_RESULT"
        const val RESULT_CONSENT_TYPE = "RESULT_CONSENT_TYPE"
        const val RESULT_CONFIRMED = "RESULT_CONFIRMED"
        private const val ARG_TITLE = "ARG_TITLE"
        private const val ARG_BODY = "ARG_BODY"
        private const val ARG_CANCEL = "ARG_CANCEL"
        private const val ARG_CONFIRM = "ARG_CONFIRM"
        private const val ARG_CONSENT_TYPE = "ARG_CONSENT_TYPE"

        fun show(
            fragmentManager: FragmentManager,
            title: Int,
            body: Int,
            cancel: Int,
            confirm: Int,
            consentType: String,
        ) {
            SettingsPrivacyConsentDialog().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TITLE, title)
                    putInt(ARG_BODY, body)
                    putInt(ARG_CANCEL, cancel)
                    putInt(ARG_CONFIRM, confirm)
                    putString(ARG_CONSENT_TYPE, consentType)
                }
            }.show(fragmentManager, SettingsPrivacyConsentDialog::class.java.name)
        }
    }
}
