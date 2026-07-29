package com.discord.widgets.settings.account.mfa

import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.widgets.settings.SettingsStrings

class WidgetEnableMFADownload : AppFragment(R.layout.widget_settings_enable_mfa_download) {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<TextView>(R.id.mfa_download_body).apply {
            text = SettingsStrings.render(
                view.context,
                R.string.two_fa_download_app_body,
                "authyURL" to AUTHY_URL,
                "googleAuthURL" to GOOGLE_AUTHENTICATOR_URL,
            )
            movementMethod = LinkMovementMethod.getInstance()
        }
    }

    companion object {
        private const val AUTHY_URL = "https://play.google.com/store/apps/details?id=com.authy.authy"
        private const val GOOGLE_AUTHENTICATOR_URL =
            "https://play.google.com/store/apps/details?id=com.google.android.apps.authenticator2"
    }
}
