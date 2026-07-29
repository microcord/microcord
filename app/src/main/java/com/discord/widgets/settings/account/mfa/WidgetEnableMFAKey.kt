package com.discord.widgets.settings.account.mfa

import android.content.pm.PackageManager
import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.app.appActivityViewModels
import com.discord.widgets.settings.SettingsStrings

class WidgetEnableMFAKey : AppFragment(R.layout.widget_settings_enable_mfa_key) {
    private val viewModel by appActivityViewModels { WidgetEnableMFAViewModel() }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<TextView>(R.id.enable_mfa_key_code).apply {
            text = viewModel.getTotpSecret()
            setOnClickListener {
                AppToast.copyToClipboard(
                    context,
                    WidgetEnableMFAViewModel.normalizeTotpSecret(text.toString()),
                )
            }
        }
        configureAuthenticatorLink(view, R.id.enable_mfa_key_launch_authy, AUTHY_PACKAGE, R.string.two_fa_app_name_authy)
        configureAuthenticatorLink(
            view,
            R.id.enable_mfa_key_launch_google_auth,
            GOOGLE_AUTHENTICATOR_PACKAGE,
            R.string.two_fa_app_name_google_authenticator,
        )
    }

    private fun configureAuthenticatorLink(view: View, viewId: Int, packageName: String, appName: Int) {
        val packageManager = view.context.packageManager
        if (!isPackageInstalled(packageName, packageManager)) return
        view.findViewById<TextView>(viewId).apply {
            text = SettingsStrings.render(context, R.string.launch_app, "app" to getString(appName))
            visibility = View.VISIBLE
            setOnClickListener {
                packageManager.getLaunchIntentForPackage(packageName)?.let(context::startActivity)
            }
        }
    }

    companion object {
        private const val AUTHY_PACKAGE = "com.authy.authy"
        private const val GOOGLE_AUTHENTICATOR_PACKAGE = "com.google.android.apps.authenticator2"

        @Suppress("DEPRECATION")
        internal fun isPackageInstalled(packageName: String, packageManager: PackageManager): Boolean = try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
