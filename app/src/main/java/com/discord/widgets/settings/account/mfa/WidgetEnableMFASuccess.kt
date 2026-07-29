package com.discord.widgets.settings.account.mfa

import android.view.View
import com.discord.R
import com.discord.app.AppFragment
import com.discord.widgets.user.account.WidgetUserAccountVerifyBase
import com.discord.widgets.user.phone.WidgetUserPhoneManage

class WidgetEnableMFASuccess : AppFragment(R.layout.widget_settings_enable_mfa_success) {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<View>(R.id.mfa_success_enable_sms).setOnClickListener {
            WidgetUserPhoneManage.launch(
                it.context,
                WidgetUserAccountVerifyBase.Mode.NO_HISTORY_FROM_USER_SETTINGS,
                WidgetUserPhoneManage.Source.MFA_PHONE_UPDATE,
            )
            requireActivity().finish()
        }
    }
}
